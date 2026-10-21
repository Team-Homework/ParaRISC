package firrtl
import firrtl.annotations.{ModuleTarget,SingleTargetAnnotation}

// CIRCT-supported hierarchy attribute for local frontend logic.
case class AttributeAnnotation(target: ModuleTarget, description: String)
  extends SingleTargetAnnotation[ModuleTarget] {
  override def duplicate(n: ModuleTarget): AttributeAnnotation = copy(target=n)
}
