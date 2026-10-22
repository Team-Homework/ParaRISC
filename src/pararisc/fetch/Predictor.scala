package pararisc.fetch

import chisel3._
import chisel3.util._

/** Update from each entry's own state, with banked training decode. */
class PredictorCounter extends LocalLogic {
  val io = IO(new Bundle {
    val bank = Input(Bool())
    val row = Input(Bool())
    val taken = Input(Bool())
    val value = Output(UInt(2.W))
  })

  val value = RegInit(2.U(2.W))
  when(io.bank && io.row) {
    when(io.taken) {
      when(value =/= 3.U) {
        value := value + 1.U
      }
    }.otherwise {
      when(value =/= 0.U) {
        value := value - 1.U
      }
    }
  }

  io.value := value
}

class PredictorHistory extends LocalLogic {
  val io = IO(new Bundle {
    val bank = Input(Bool())
    val row = Input(Bool())
    val taken = Input(Bool())
    val value = Output(UInt(2.W))
  })

  val value = RegInit(0.U(2.W))
  when(io.bank && io.row) {
    value := Cat(value(0), io.taken)
  }

  io.value := value
}

/** Snapshot stored with each accepted fetch; none of these fields are live
  * predictor outputs by the time the instruction response is consumed.
  */
class BranchPrediction extends Bundle {
  val next = UInt(32.W)
  val history = UInt(2.W)
  val callApplied = Bool()
  val returnApplied = Bool()
  val rasTop = UInt(32.W)
  val rasValid = Bool()
}

class PredictionResponse extends Bundle {
  val pc = UInt(32.W)
  val inst = UInt(32.W)
  val prediction = new BranchPrediction
}

/** BTB, local-history direction predictor, predecode repair, and return stack.
  * Queries and correction are combinational. `accept` and `response.valid`
  * denote actual, non-canceled fetch handshakes, not offers under backpressure.
  * Training updates direction/history at the accepting edge; the banked BTB
  * publishes its registered payload at the following edge. Redirect restores
  * the committed RAS, including a call/return committing on that same edge.
  */
class BranchPredictor(p: FetchConfig) extends Module {
  val io = IO(new Bundle {
    val pc = Input(UInt(32.W))
    val prediction = Output(new BranchPrediction)
    val accept = Input(Bool())
    val response = Flipped(Valid(new PredictionResponse))
    val corrected = Output(UInt(32.W))
    val redirect = Input(Bool())
    val train = Flipped(Valid(new Training))
    val commit = Flipped(Valid(new Training))
  })

  val pc = io.pc
  val response = io.response.bits
  val entries = p.btbEntries
  val idxBits = log2Ceil(entries)
  val bvalid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  val btag = Reg(Vec(entries, UInt((30 - idxBits).W)))
  val btarget = Reg(Vec(entries, UInt(30.W)))
  val bcond = Reg(Vec(entries, Bool()))
  val bret = Reg(Vec(entries, Bool()))
  val bcall = Reg(Vec(entries, Bool()))
  val counterEntries = Seq.fill(256)(Module(new PredictorCounter))
  val historyEntries = Seq.fill(64)(Module(new PredictorHistory))
  val counters = VecInit(counterEntries.map(_.io.value))
  val history = VecInit(historyEntries.map(_.io.value))

  def predictionIndex(address: UInt, h: UInt): UInt =
    if(p.historyBits == 0) address(9, 2)
    else Cat(address(9 - p.historyBits, 2), h(p.historyBits - 1, 0))

  val sp = RegInit(0.U(3.W))
  val depth = RegInit(0.U(4.W))
  val speculativeRas = Seq.tabulate(8)(i => Module(new LocalIndexedWord(32, 3, i)))
  val committedRas = Seq.tabulate(8)(i => Module(new LocalIndexedWord(32, 3, i)))
  val rasValid = RegInit(VecInit(Seq.fill(8)(false.B)))
  val committedSp = RegInit(0.U(3.W))
  val committedDepth = RegInit(0.U(4.W))

  // Recovery switches entry ownership instead of copying 8 wide words.
  val pendingRas = Wire(Valid(new Bundle {
    val index = UInt(3.W)
    val data = UInt(32.W)
  }))
  val rasTopIndex = (sp - 1.U)(2, 0)
  val storedRasTop = Util.choose(rasTopIndex, (0 until 8).map(i =>
    Mux(rasValid(i), speculativeRas(i).io.value, committedRas(i).io.value)
  ))
  val rasTop = if(p.rasWriteStages == 1) Mux(
    pendingRas.valid && pendingRas.bits.index === rasTopIndex && rasValid(rasTopIndex),
    pendingRas.bits.data,
    storedRasTop
  ) else storedRasTop

  val ix = pc(idxBits + 1, 2)
  val targetHits = VecInit((0 until entries).map(i =>
    ix === i.U && bvalid(i) && btag(i) === pc(31, idxBits + 2)
  ))
  val bpHit = targetHits.asUInt.orR
  val conditionalPrediction = Mux1H(targetHits, bcond)
  val returnPrediction = Mux1H(targetHits, bret)
  val callPrediction = Mux1H(targetHits, bcall)
  val fetchHistory = history(pc(7, 2))
  val counterIndex = predictionIndex(pc, fetchHistory)
  val taken = bpHit && (!conditionalPrediction || counters(counterIndex)(1))
  val target = Mux(
    returnPrediction && depth =/= 0.U,
    rasTop,
    Cat(Mux1H(targetHits, btarget), 0.U(2.W))
  )
  val predicted = Mux(taken, target, pc + 4.U)

  io.prediction.next := predicted
  io.prediction.history := fetchHistory
  io.prediction.callApplied := taken && callPrediction
  io.prediction.returnApplied := taken && returnPrediction
  io.prediction.rasTop := rasTop
  io.prediction.rasValid := depth =/= 0.U

  val incoming = response.inst
  val incomingPC = response.pc
  val isJal = incoming(6, 0) === "h6f".U
  val isBranch = incoming(6, 0) === "h63".U
  val isJalr = incoming(6, 0) === "h67".U
  val call = (isJal || isJalr) && (incoming(11, 7) === 1.U || incoming(11, 7) === 5.U)
  val ret = isJalr && (incoming(19, 15) === 1.U || incoming(19, 15) === 5.U) &&
    incoming(11, 7) =/= incoming(19, 15)
  val jalTarget = incomingPC + Util.sext(Cat(
    incoming(31), incoming(19, 12), incoming(20), incoming(30, 21), 0.U(1.W)
  ))
  val branchTarget = incomingPC + Util.sext(Cat(
    incoming(31), incoming(7), incoming(30, 25), incoming(11, 8), 0.U(1.W)
  ))
  val responseDirection = counters(predictionIndex(incomingPC, response.prediction.history))(1)
  val corrected = Mux(
    isJal,
    jalTarget,
    Mux(
      isBranch,
      Mux(responseDirection, branchTarget, incomingPC + 4.U),
      Mux(ret && response.prediction.rasValid, response.prediction.rasTop, response.prediction.next)
    )
  )

  io.corrected := corrected

  val responseCall = io.response.valid && call && !response.prediction.callApplied
  val predictionCall = io.accept && taken && callPrediction

  if(p.rasWriteStages == 1) {
    pendingRas.valid := RegNext(responseCall || predictionCall, false.B)
    pendingRas.bits.index := RegNext(sp)
    val predictionData = RegNext(pc + 4.U)
    val responseData = RegNext(incomingPC + 4.U)
    val predictionSelected = RegNext(predictionCall, false.B)
    pendingRas.bits.data := Mux(predictionSelected, predictionData, responseData)
  } else {
    pendingRas.valid := responseCall || predictionCall
    pendingRas.bits.index := sp
    pendingRas.bits.data := Mux(predictionCall, pc + 4.U, incomingPC + 4.U)
  }

  for(i <- 0 until 8) {
    speculativeRas(i).io.index := pendingRas.bits.index
    speculativeRas(i).io.write := pendingRas.valid
    speculativeRas(i).io.data := pendingRas.bits.data
    when((responseCall || predictionCall) && sp === i.U) {
      rasValid(i) := true.B
    }
  }

  when(io.response.valid) {
    when(call && !response.prediction.callApplied) {
      sp := sp + 1.U
      when(depth < 8.U) {
        depth := depth + 1.U
      }
    }
    when(ret && !response.prediction.returnApplied && depth =/= 0.U) {
      sp := sp - 1.U
      depth := depth - 1.U
    }
  }

  when(io.accept) {
    when(taken && callPrediction) {
      sp := sp + 1.U
      when(depth < 8.U) {
        depth := depth + 1.U
      }
    }
    when(taken && returnPrediction && depth =/= 0.U) {
      sp := sp - 1.U
      depth := depth - 1.U
    }
  }

  val t = io.train.bits
  val ti = t.pc(idxBits + 1, 2)
  val ci = predictionIndex(t.pc, t.history)

  // Decode writes before the register boundary, and bound payload fanout to
  // eight table entries. The registered row mask publishes prepared data.
  val predictorWrites = RegNext(
    UIntToOH(ti, entries) & Fill(entries, io.train.valid),
    0.U(entries.W)
  )
  val predictorPayload = Reg(Vec(entries / 8, new Bundle {
    val tag = UInt((30 - idxBits).W)
    val target = UInt(30.W)
    val conditional = Bool()
    val ret = Bool()
    val call = Bool()
  }))

  for(b <- 0 until entries / 8) {
    when(ti(idxBits - 1, 3) === b.U) {
      predictorPayload(b).tag := t.pc(31, idxBits + 2)
      predictorPayload(b).target := t.target(31, 2)
      predictorPayload(b).conditional := t.conditional
      predictorPayload(b).ret := t.ret
      predictorPayload(b).call := t.call
    }
  }

  for(i <- 0 until entries) {
    when(predictorWrites(i)) {
      bvalid(i) := true.B
      btag(i) := predictorPayload(i / 8).tag
      btarget(i) := predictorPayload(i / 8).target
      bcond(i) := predictorPayload(i / 8).conditional
      bret(i) := predictorPayload(i / 8).ret
      bcall(i) := predictorPayload(i / 8).call
    }
  }

  val counterBanks = (0 until 16).map(b => io.train.valid && t.conditional && ci(7, 4) === b.U)
  val counterRows = (0 until 16).map(r => ci(3, 0) === r.U)
  val counterDirections = (0 until 16).map(b => t.taken && ci(7, 4) === b.U)

  for(i <- 0 until 256) {
    counterEntries(i).io.bank := counterBanks(i / 16)
    counterEntries(i).io.row := counterRows(i % 16)
    counterEntries(i).io.taken := counterDirections(i / 16)
  }

  val historyBanks = (0 until 8).map(b => io.train.valid && t.conditional && t.pc(7, 5) === b.U)
  val historyRows = (0 until 8).map(r => t.pc(4, 2) === r.U)
  val historyDirections = (0 until 8).map(b => t.taken && t.pc(7, 5) === b.U)

  for(i <- 0 until 64) {
    historyEntries(i).io.bank := historyBanks(i / 8)
    historyEntries(i).io.row := historyRows(i % 8)
    historyEntries(i).io.taken := historyDirections(i / 8)
  }

  val c = io.commit.bits
  for(i <- 0 until 8) {
    committedRas(i).io.write := io.commit.valid && c.call
    committedRas(i).io.index := committedSp
    committedRas(i).io.data := c.pc + 4.U
  }

  when(io.commit.valid && c.call) {
    committedSp := committedSp + 1.U
    when(committedDepth < 8.U) {
      committedDepth := committedDepth + 1.U
    }
  }
  when(io.commit.valid && c.ret && committedDepth =/= 0.U) {
    committedSp := committedSp - 1.U
    committedDepth := committedDepth - 1.U
  }

  when(io.redirect) {
    rasValid.foreach(_ := false.B)
    sp := committedSp
    depth := committedDepth
    when(io.commit.valid && c.call) {
      sp := committedSp + 1.U
      depth := Mux(committedDepth < 8.U, committedDepth + 1.U, committedDepth)
    }
    when(io.commit.valid && c.ret && committedDepth =/= 0.U) {
      sp := committedSp - 1.U
      depth := committedDepth - 1.U
    }
  }
}
