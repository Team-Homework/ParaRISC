package pararisc.fetch

import chisel3._
import chisel3.util._

/** Single-issue frontend adapted from ParaRisc single-issue-chisel. */
class FetchPacket extends Bundle {
  val pc = UInt(32.W)
  val inst = UInt(32.W)
  val next = UInt(32.W)
  val history = UInt(2.W)
}

class Training extends Bundle {
  val pc = UInt(32.W)
  val target = UInt(32.W)
  val taken = Bool()
  val conditional = Bool()
  val call = Bool()
  val ret = Bool()
  val history = UInt(2.W)
}

class Frontend(p: FetchConfig) extends Module {
  val io = IO(new Bundle {
    val out = Decoupled(new FetchPacket)
    val mem = new MemPort
    val redirect = Flipped(Valid(UInt(32.W)))
    val train = Flipped(Valid(new Training))
    val commit = Flipped(Valid(new Training))
  })

  val cache = Module(new Cache(
    p.icBytes,
    1,
    p.lineBytes,
    readonly = true,
    readAhead = p.ramReadAhead,
    preserveHierarchy = p.cacheHierarchy
  ))
  io.mem <> cache.io.mem

  val pc = RegInit(0.U(32.W))

  // Keep invalidation sticky for each outstanding request. A wrapping epoch
  // can otherwise revive an old response after repeated backend redirects.
  val metaHead = RegInit(0.U(1.W))
  val metaTail = RegInit(0.U(1.W))
  val invalidated = RegInit(VecInit(Seq.fill(2)(false.B)))

  val predictor = Module(new BranchPredictor(p))
  predictor.io.pc := pc
  predictor.io.redirect := io.redirect.valid
  predictor.io.train <> io.train
  predictor.io.commit <> io.commit

  val meta = Module(new PreparedQueue(new Bundle {
    val pc = UInt(32.W)
    val prediction = new BranchPrediction
  }, 2))
  val fetched = withReset(reset.asBool || io.redirect.valid) {
    Module(new PreparedQueue(
      new FetchPacket,
      8,
      flow = true,
      headSliceBits = p.fetchHeadSliceBits,
      debugHead = p.debug,
      cachedHead = p.cachedFetchHead,
      cacheLow = 34,
      cacheWidth = if(p.cachedFetchInstruction) 32 else 0
    ))
  }

  io.out.bits := fetched.io.deq.bits
  io.out.valid := fetched.io.deq.valid && !io.redirect.valid
  fetched.io.deq.ready := io.out.ready && !io.redirect.valid

  val repair = Wire(Bool())
  cache.io.cpu.req.valid := meta.io.enq.ready && !io.redirect.valid && !repair
  cache.io.cpu.req.bits := 0.U.asTypeOf(new MemReq)
  cache.io.cpu.req.bits.addr := pc
  meta.io.enq.valid := cache.io.cpu.req.fire
  meta.io.enq.bits.pc := pc
  meta.io.enq.bits.prediction := predictor.io.prediction
  predictor.io.accept := cache.io.cpu.req.fire

  val stale = invalidated(metaHead) || io.redirect.valid
  fetched.io.enq.valid := cache.io.cpu.rsp.valid && meta.io.deq.valid && !stale
  predictor.io.response.valid := cache.io.cpu.rsp.fire && !stale
  predictor.io.response.bits.pc := meta.io.deq.bits.pc
  predictor.io.response.bits.inst := cache.io.cpu.rsp.bits.data
  predictor.io.response.bits.prediction := meta.io.deq.bits.prediction

  fetched.io.enq.bits.pc := meta.io.deq.bits.pc
  fetched.io.enq.bits.next := predictor.io.corrected
  fetched.io.enq.bits.history := meta.io.deq.bits.prediction.history
  fetched.io.enq.bits.inst := cache.io.cpu.rsp.bits.data
  cache.io.cpu.rsp.ready := meta.io.deq.valid && (stale || fetched.io.enq.ready)
  meta.io.deq.ready := cache.io.cpu.rsp.fire
  repair := cache.io.cpu.rsp.fire && !stale && predictor.io.corrected =/= meta.io.deq.bits.prediction.next

  when(meta.io.enq.fire) {
    invalidated(metaTail) := false.B
    metaTail := metaTail + 1.U
  }
  when(meta.io.deq.fire) {
    metaHead := metaHead + 1.U
  }
  when(repair || io.redirect.valid) {
    invalidated.foreach(_ := true.B)
  }

  when(cache.io.cpu.req.fire) {
    pc := predictor.io.prediction.next
  }
  when(repair) {
    pc := predictor.io.corrected
  }
  when(io.redirect.valid) {
    pc := io.redirect.bits
  }
}
