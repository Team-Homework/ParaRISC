package cpu2026

import chisel3._

/** The user's recursive carry-lookahead tree, packaged for reuse by execution units. */
class RecursiveCLA32 extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(32.W))
    val b = Input(UInt(32.W))
    val cin = Input(Bool())
    val sum = Output(UInt(32.W))
    val cout = Output(Bool())
  })

  // Scala objects describe the elaboration tree; p and g are hardware signals.
  case class Group(lo: Int, p: Bool, g: Bool, left: Option[Group], right: Option[Group])

  def build(lo: Int, hi: Int): Group = {
    if (hi - lo == 1) {
      Group(lo, io.a(lo) ^ io.b(lo), io.a(lo) & io.b(lo), None, None)
    } else {
      val mid = (lo + hi) / 2
      val l = build(lo, mid)
      val r = build(mid, hi)
      Group(lo, l.p & r.p, r.g | (r.p & l.g), Some(l), Some(r))
    }
  }

  val root = build(0, 32)
  val sums = Wire(Vec(32, Bool()))

  def fillSums(group: Group, cin: Bool): Unit = {
    group.left match {
      case None => sums(group.lo) := group.p ^ cin
      case Some(l) =>
        val r = group.right.get
        val rightCin = l.g | (l.p & cin)
        fillSums(l, cin)
        fillSums(r, rightCin)
    }
  }

  fillSums(root, io.cin)
  io.sum := sums.asUInt
  io.cout := root.g | (root.p & io.cin)
}

/** Emit this leaf module without changing the course's student_top generator. */
object GenerateAdder32 {
  def main(args: Array[String]): Unit = {
    circt.stage.ChiselStage.emitSystemVerilogFile(new RecursiveCLA32, args)
  }
}
