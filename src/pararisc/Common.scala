package pararisc

import chisel3._
import chisel3.util._

object Util {
  /** 符号扩展到指定位宽。 */
  def sext(x: UInt, width: Int = 32): UInt =
    Cat(Fill(width - x.getWidth, x(x.getWidth - 1)), x)
}
