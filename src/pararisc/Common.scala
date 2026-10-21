package pararisc

import chisel3._
import chisel3.util._

object Util {
  /** Preserve the input sign bit while extending an immediate to XLEN. */
  def sext(x: UInt, width: Int = 32): UInt =
    Cat(Fill(width - x.getWidth, x(x.getWidth - 1)), x)
}
