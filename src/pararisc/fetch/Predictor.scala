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
