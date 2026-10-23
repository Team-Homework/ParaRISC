package pararisc

import chisel3._
import chisel3.experimental.annotate

/** 综合时保留模块边界。 */
abstract class LocalLogic extends Module {
  private val owner = this

  annotate(owner)(Seq(
    firrtl.AttributeAnnotation(owner.toNamed, "keep_hierarchy = 1")
  ))
}
