package pararisc.fetch

import chisel3._
import chisel3.util._

class MemReq extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val mask = UInt(4.W)
  val write = Bool()
}

class MemRsp extends Bundle {
  val data = UInt(32.W)
  val error = Bool()
}

class MemPort extends Bundle {
  val req = Decoupled(new MemReq)
  val rsp = Flipped(Decoupled(new MemRsp))
}

/** The course supplies both the behavioral model and the counted SRAM Liberty. */
class Sram(depth: Int, width: Int = 32, granularity: Int = 8)
  extends BlackBox(Map(
    "DEPTH" -> depth,
    "WIDTH" -> width,
    "WRITE_GRANULARITY" -> granularity
  )) {
  override def desiredName = "sram_fakeram"

  val io = IO(new Bundle {
    val clk = Input(Clock())
    val en = Input(Bool())
    val we = Input(Bool())
    val wmask = Input(UInt((width / granularity).W))
    val addr = Input(UInt(log2Ceil(depth).W))
    val wdata = Input(UInt(width.W))
    val rdata = Output(UInt(width.W))
  })
}

object Util {
  def sext(x: UInt, width: Int = 32): UInt =
    Cat(Fill(width - x.getWidth, x(x.getWidth - 1)), x)

  def select[T <: Data](selects: Seq[Bool], values: Seq[T]): T = {
    val width = values.head.getWidth
    val masked = selects.zip(values).map { case (s, v) =>
      val word = Module(new LocalMask(width))
      word.io.selected := s
      word.io.word := v.asUInt
      word.io.value
    }
    masked.reduce(_ | _).asTypeOf(values.head)
  }
  def choose[T <: Data](index: UInt, values: Seq[T]): T =
    select(values.indices.map(i => index === i.U), values)
}
