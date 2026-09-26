package cpu2026

import chisel3._
import chisel3.util._

/** Local operation encoding; the decoder must translate RV32I funct fields into these values. */
object AluOp {
  val ADD  = 0.U(4.W)
  val SUB  = 1.U(4.W)
  val SLT  = 2.U(4.W)
  val SLTU = 3.U(4.W)
  val XOR  = 4.U(4.W)
  val OR   = 5.U(4.W)
  val AND  = 6.U(4.W)
  val SLL  = 7.U(4.W)
  val SRL  = 8.U(4.W)
  val SRA  = 9.U(4.W)
  val COPY_B = 10.U(4.W) // LUI; AUIPC uses ADD with a=pc.
}

/** One-cycle integer ALU. The CLA is shared by add, subtract and comparisons. */
class IntegerAlu extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W)) // rs2 or the already sign-extended immediate
    val op = Input(UInt(4.W))
    val result = Output(UInt(32.W))
    val equal = Output(Bool())
    val lessSigned = Output(Bool()) // comparison outputs valid for SUB/SLT/SLTU
    val lessUnsigned = Output(Bool())
  })

  val subtract = io.op === AluOp.SUB || io.op === AluOp.SLT || io.op === AluOp.SLTU
  val adder = Module(new RecursiveCLA32)
  adder.io.a := io.a
  adder.io.b := Mux(subtract, ~io.b, io.b)
  adder.io.cin := subtract

  // Subtraction carry-out means no unsigned borrow. Signed comparison needs
  // sign correction when the operands have different sign bits.
  io.equal := io.a === io.b
  io.lessUnsigned := !adder.io.cout
  io.lessSigned := Mux(io.a(31) =/= io.b(31), io.a(31), adder.io.sum(31))

  io.result := 0.U
  switch(io.op) {
    is(AluOp.ADD, AluOp.SUB) { io.result := adder.io.sum }
    is(AluOp.SLT) { io.result := io.lessSigned.asUInt }
    is(AluOp.SLTU) { io.result := io.lessUnsigned.asUInt }
    is(AluOp.XOR) { io.result := io.a ^ io.b }
    is(AluOp.OR) { io.result := io.a | io.b }
    is(AluOp.AND) { io.result := io.a & io.b }
    is(AluOp.SLL) { io.result := io.a << io.b(4, 0) }
    is(AluOp.SRL) { io.result := io.a >> io.b(4, 0) }
    is(AluOp.SRA) { io.result := (io.a.asSInt >> io.b(4, 0)).asUInt }
    is(AluOp.COPY_B) { io.result := io.b }
  }
}

object BranchOp {
  val NONE = 0.U(4.W)
  val BEQ  = 1.U(4.W)
  val BNE  = 2.U(4.W)
  val BLT  = 3.U(4.W)
  val BGE  = 4.U(4.W)
  val BLTU = 5.U(4.W)
  val BGEU = 6.U(4.W)
  val JAL  = 7.U(4.W)
  val JALR = 8.U(4.W)
}

/** Branch condition and architectural next PC. No predictor state lives here. */
class BranchUnit extends Module {
  val io = IO(new Bundle {
    val pc = Input(UInt(32.W))
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val offset = Input(UInt(32.W)) // already sign-extended B/J/I immediate
    val op = Input(UInt(4.W))
    val taken = Output(Bool())
    val nextPc = Output(UInt(32.W))
    val link = Output(UInt(32.W))
    val targetMisaligned = Output(Bool())
  })

  val cmp = Module(new IntegerAlu)
  cmp.io.a := io.a
  cmp.io.b := io.b
  cmp.io.op := AluOp.SLT
  val targetAdder = Module(new RecursiveCLA32)
  targetAdder.io.a := Mux(io.op === BranchOp.JALR, io.a, io.pc)
  targetAdder.io.b := io.offset
  targetAdder.io.cin := false.B
  val linkAdder = Module(new RecursiveCLA32)
  linkAdder.io.a := io.pc
  linkAdder.io.b := 4.U
  linkAdder.io.cin := false.B
  io.link := linkAdder.io.sum

  io.taken := false.B
  switch(io.op) {
    is(BranchOp.BEQ) { io.taken := cmp.io.equal }
    is(BranchOp.BNE) { io.taken := !cmp.io.equal }
    is(BranchOp.BLT) { io.taken := cmp.io.lessSigned }
    is(BranchOp.BGE) { io.taken := !cmp.io.lessSigned }
    is(BranchOp.BLTU) { io.taken := cmp.io.lessUnsigned }
    is(BranchOp.BGEU) { io.taken := !cmp.io.lessUnsigned }
    is(BranchOp.JAL, BranchOp.JALR) { io.taken := true.B }
  }
  val target = Mux(io.op === BranchOp.JALR,
    targetAdder.io.sum & "hfffffffe".U(32.W), targetAdder.io.sum)
  io.nextPc := Mux(io.taken, target, io.link)
  // RV32IM has no compressed instructions, so taken targets must be 4-byte aligned.
  io.targetMisaligned := io.taken && target(1, 0) =/= 0.U
}

object AccessSize {
  val BYTE = 0.U(2.W)
  val HALF = 1.U(2.W)
  val WORD = 2.U(2.W)
}

/** LSU address and little-endian store lane calculation; no memory request here. */
class AddressGenerator extends Module {
  val io = IO(new Bundle {
    val base = Input(UInt(32.W))
    val offset = Input(UInt(32.W)) // already sign-extended I/S immediate
    val storeData = Input(UInt(32.W))
    val size = Input(UInt(2.W))
    val address = Output(UInt(32.W))
    val wordAddress = Output(UInt(32.W)) // AXI4-Lite requires 4-byte alignment
    val byteLane = Output(UInt(2.W))
    val writeMask = Output(UInt(4.W))
    val shiftedStoreData = Output(UInt(32.W))
    val misaligned = Output(Bool())
    val sizeSupported = Output(Bool())
  })

  val adder = Module(new RecursiveCLA32)
  adder.io.a := io.base
  adder.io.b := io.offset
  adder.io.cin := false.B
  io.address := adder.io.sum
  io.wordAddress := Cat(adder.io.sum(31, 2), 0.U(2.W))
  io.byteLane := adder.io.sum(1, 0)
  val shift = Cat(io.byteLane, 0.U(3.W))
  io.shiftedStoreData := (io.storeData << shift)(31, 0)

  io.writeMask := 0.U
  io.misaligned := false.B
  io.sizeSupported := io.size =/= 3.U
  switch(io.size) {
    is(AccessSize.BYTE) { io.writeMask := (1.U(4.W) << io.byteLane)(3, 0) }
    is(AccessSize.HALF) {
      io.writeMask := (3.U(4.W) << io.byteLane)(3, 0)
      io.misaligned := io.byteLane(0)
    }
    is(AccessSize.WORD) {
      io.writeMask := "b1111".U
      io.misaligned := io.byteLane =/= 0.U
    }
  }
}
