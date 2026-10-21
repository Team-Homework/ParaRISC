package pararisc.fetch
import chisel3._
import chisel3.util.Fill
import chisel3.experimental.{annotate,ChiselAnnotation}

/** Preserve local decode/feedback boundaries through the ordinary course flow.
  * These are mapped, counted Chisel logic, with no timing or area exclusions.
  */
abstract class LocalLogic extends Module {
  private val owner = this
  annotate(new ChiselAnnotation {
    override def toFirrtl: firrtl.annotations.Annotation =
      firrtl.AttributeAnnotation(owner.toTarget,"keep_hierarchy = 1")
  })
}
class LocalMask(width: Int) extends Module {
  val io = IO(new Bundle {
    val selected = Input(Bool()); val word = Input(UInt(width.W)); val value = Output(UInt(width.W))
  })
  io.value := io.word & Fill(width,io.selected)
}
class LocalWord(width: Int) extends Module {
  val io = IO(new Bundle {
    val prepare = Input(Bool()); val data = Input(UInt(width.W)); val value = Output(UInt(width.W))
  })
  val value = Reg(UInt(width.W))
  when(io.prepare) { value := io.data }
  io.value := value
}
/** A local ring pointer keeps a queue's read select near one payload slice. */
class LocalQueueHead(entries: Int) extends LocalLogic {
  require(entries >= 2)
  val io = IO(new Bundle { val step = Input(Bool()); val value = Output(UInt(entries.W)) })
  val value = RegInit(1.U(entries.W))
  when(io.step) { value := chisel3.util.Cat(value(entries-2,0),value(entries-1)) }
  io.value := value
}
class LocalIndexedWord(width: Int, indexBits: Int, index: Int) extends LocalLogic {
  val io = IO(new Bundle {
    val write = Input(Bool()); val index = Input(UInt(indexBits.W)); val data = Input(UInt(width.W)); val value = Output(UInt(width.W))
  })
  val value = Reg(UInt(width.W))
  when(io.write && io.index === index.U) { value := io.data }
  io.value := value
}
