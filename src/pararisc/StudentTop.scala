package pararisc

import chisel3._

/** Flat ports required by the course simulator and synthesis framework. */
abstract class CourseTop extends RawModule {
  override def desiredName: String = "student_top"

  val clock = IO(Input(Clock()))
  val reset = IO(Input(Bool()))
  val araddr = IO(Output(UInt(32.W)))
  val arvalid = IO(Output(Bool()))
  val arready = IO(Input(Bool()))
  val rdata = IO(Input(UInt(32.W)))
  val rresp = IO(Input(UInt(2.W)))
  val rvalid = IO(Input(Bool()))
  val rready = IO(Output(Bool()))
  val awaddr = IO(Output(UInt(32.W)))
  val awvalid = IO(Output(Bool()))
  val awready = IO(Input(Bool()))
  val wdata = IO(Output(UInt(32.W)))
  val wstrb = IO(Output(UInt(4.W)))
  val wvalid = IO(Output(Bool()))
  val wready = IO(Input(Bool()))
  val bresp = IO(Input(UInt(2.W)))
  val bvalid = IO(Input(Bool()))
  val bready = IO(Output(Bool()))
}

/** Starter only: replace the idle outputs with the CPU implementation. */
class StudentTop extends CourseTop {
  araddr := 0.U
  arvalid := false.B
  rready := true.B
  awaddr := 0.U
  awvalid := false.B
  wdata := 0.U
  wstrb := 0.U
  wvalid := false.B
  bready := true.B
}
