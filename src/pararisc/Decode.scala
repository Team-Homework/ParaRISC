package pararisc

import chisel3._
import chisel3.util._
import scala.language.reflectiveCalls

/** 指令译码后的控制信息。 */
class Decoded extends Bundle {
  val rd = UInt(5.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val use1 = Bool()
  val use2 = Bool()
  val writes = Bool()
  val imm = UInt(32.W)
  val fn = UInt(3.W) // 运算类型
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

/** 从指令中提取寄存器编号、立即数和控制信号。 */
class Decoder extends Module {
  val io = IO(new Bundle {
    val inst = Input(UInt(32.W))
    val d = Output(new Decoded)
  })

  val inst = io.inst
  val opcode = inst(6, 0)
  val d = io.d

  // 先清空控制信号，默认标记为非法。
  d := 0.U.asTypeOf(d)
  d.rd := inst(11, 7)
  d.rs1 := inst(19, 15)
  d.rs2 := inst(24, 20)
  d.fn := inst(14, 12)
  d.imm := Util.sext(inst(31, 20))
  d.illegal := true.B

  switch(opcode) {
    is("h37".U) { // LUI：加载高位立即数
      d.lui := true.B
      d.imm := Cat(inst(31, 12), 0.U(12.W))
      d.writes := true.B
      d.illegal := false.B
    }
    is("h17".U) { // AUIPC：PC 加立即数
      d.pcA := true.B
      d.immB := true.B
      d.fn := 0.U
      d.imm := Cat(inst(31, 12), 0.U(12.W))
      d.writes := true.B
      d.illegal := false.B
    }
    is("h6f".U) { // JAL：直接跳转
      d.jal := true.B
      d.imm := Util.sext(Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)))
      d.writes := true.B
      d.illegal := false.B
    }
    is("h67".U) { // JALR：寄存器地址跳转
      d.jalr := true.B
      d.use1 := true.B
      d.writes := true.B
      d.illegal := inst(14, 12) =/= 0.U
    }
    is("h63".U) { // 条件分支
      d.branch := true.B
      d.use1 := true.B
      d.use2 := true.B
      d.imm := Util.sext(Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)))
      d.illegal := inst(14, 13) === 1.U
    }
    is("h03".U) { // 读内存，fn 决定宽度和符号扩展
      d.load := true.B
      d.use1 := true.B
      d.writes := true.B
      d.illegal := inst(14, 12) === 3.U || inst(14, 12) >= 6.U
    }
    is("h23".U) { // 写内存
      d.store := true.B
      d.use1 := true.B
      d.use2 := true.B
      d.imm := Util.sext(Cat(inst(31, 25), inst(11, 7)))
      d.illegal := inst(14, 12) > 2.U
    }
    is("h13".U) { // 寄存器与立即数运算
      d.use1 := true.B
      d.immB := true.B
      d.writes := true.B
      d.sub := inst(30) && inst(14, 12) === 5.U
      d.illegal := false.B
    }
    is("h33".U) { // 两个寄存器运算，含乘除法
      d.use1 := true.B
      d.use2 := true.B
      d.writes := true.B
      d.sub := inst(30)
      d.muldiv := inst(31, 25) === 1.U
      d.illegal := false.B
    }
    is("h0f".U) { // 当前按空操作处理
      d.illegal := false.B
    }
  }
}
