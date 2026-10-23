package pararisc

import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}

object Generate {
  def main(args: Array[String]): Unit = {
    var output: Option[String] = None
    var module = "top"
    var smoke = false
    var rest = args.toList
    while(rest.nonEmpty) {
      rest match {
        case "--out" :: directory :: tail =>
          output = Some(directory)
          rest = tail
        case "--module" :: name :: tail =>
          module = name
          rest = tail
        case "--smoke" :: tail =>
          smoke = true
          rest = tail
        case _ => throw new IllegalArgumentException(
          "Usage: Generate [--module top|decoder|adder|execute] [--out directory] [--smoke]")
      }
    }
    require(Set("top", "decoder", "adder", "execute")(module), s"Unknown module: $module")
    require(!smoke || module == "top", "--smoke requires --module top")
    val topName = module match {
      case "decoder" => "Decoder"
      case "adder" => "Adder32"
      case "execute" => "Execute"
      case _ => "student_top"
    }
    val defaultOutput = module match {
      case "decoder" => "build/decoder/rtl"
      case "adder" => "build/adder/rtl"
      case "execute" => "build/execute/rtl"
      case _ => "verilog/generated"
    }
    val directory = Paths.get(output.getOrElse(defaultOutput)).toAbsolutePath.normalize()
    Files.createDirectories(directory)
    ChiselStage.emitSystemVerilogFile(
      module match {
        case "decoder" => new Decoder
        case "adder" => new Adder32
        case "execute" => new Execute
        case _ => if(smoke) new EnvironmentSmokeTop else new StudentTop
      },
      Array("--target-dir", directory.toString),
      Array("--disable-all-randomization", "--strip-debug-info",
        "--lowering-options=disallowLocalVariables,disallowPackedArrays")
    )
    // 保留生成器的文件列表，包含所有子模块。
    println(s"Generated ${directory.resolve(s"$topName.sv")}")
  }
}
