package pararisc

import chisel3._
import chisel3.util._
import scala.language.reflectiveCalls

/** Control information shared by dispatch, scheduling and execution.
  * Field names, widths and ordering follow single-issue-chisel v110.
  */
class Decoded extends Bundle {
  val rd = UInt(5.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val use1 = Bool()
  val use2 = Bool()
  val writes = Bool()
  val imm = UInt(32.W)
  val fn = UInt(3.W)
  val sub = Bool()
  val pcA = Bool()
  val immB = Bool()
  val lui = Bool()
  val branch = Bool()
  val jal = Bool()
  val jalr = Bool()
  val load = Bool()
  val store = Bool()
  val muldiv = Bool()
  val illegal = Bool()
}

/** Combinational instruction decoder ported from the frozen v110 source.
  * This preserves v110's legality checks and control outputs, including on
  * illegal encodings. Core handles faults and suppresses writes to x0.
  */
class Decoder extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val d = Output(new Decoded)
  })

  val inst = io.inst
  val opcode = inst(6, 0)
  val d = io.d

  // Raw register fields remain available even when use1/use2 are false.
  d := 0.U.asTypeOf(d)
  d.rd := inst(11, 7)
  d.rs1 := inst(19, 15)
  d.rs2 := inst(24, 20)
  d.fn := inst(14, 12)
  d.imm := Util.sext(inst(31, 20))
  d.illegal := true.B

  switch(opcode) {
    is("h37".U) { // LUI
      d.lui := true.B
      d.imm := Cat(inst(31, 12), 0.U(12.W))
      d.writes := true.B
      d.illegal := false.B
    }
    is("h17".U) { // AUIPC: execute PC + U-immediate through the adder.
      d.pcA := true.B
      d.immB := true.B
      d.fn := 0.U
      d.imm := Cat(inst(31, 12), 0.U(12.W))
      d.writes := true.B
      d.illegal := false.B
    }
    is("h6f".U) { // JAL
      d.jal := true.B
      d.imm := Util.sext(Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)))
      d.writes := true.B
      d.illegal := false.B
    }
    is("h67".U) { // JALR
      d.jalr := true.B
      d.use1 := true.B
      d.writes := true.B
      d.illegal := inst(14, 12) =/= 0.U
    }
    is("h63".U) { // Conditional branches
      d.branch := true.B
      d.use1 := true.B
      d.use2 := true.B
      d.imm := Util.sext(Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)))
      d.illegal := inst(14, 13) === 1.U
    }
    is("h03".U) { // Loads; fn carries width and signedness to the load path.
      d.load := true.B
      d.use1 := true.B
      d.writes := true.B
      d.illegal := inst(14, 12) === 3.U || inst(14, 12) >= 6.U
    }
    is("h23".U) { // Stores
      d.store := true.B
      d.use1 := true.B
      d.use2 := true.B
      d.imm := Util.sext(Cat(inst(31, 25), inst(11, 7)))
      d.illegal := inst(14, 12) > 2.U
    }
    is("h13".U) { // Register-immediate ALU operations
      d.use1 := true.B
      d.immB := true.B
      d.writes := true.B
      d.sub := inst(30) && inst(14, 12) === 5.U
      d.illegal := false.B
    }
    is("h33".U) { // Register-register ALU operations and the M extension
      d.use1 := true.B
      d.use2 := true.B
      d.writes := true.B
      d.sub := inst(30)
      d.muldiv := inst(31, 25) === 1.U
      d.illegal := false.B
    }
    is("h0f".U) { // v110 accepts this opcode with no side effects.
      d.illegal := false.B
    }
  }
}
