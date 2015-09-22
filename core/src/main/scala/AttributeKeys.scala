package funnel

/**
 * Hard-coded attribute key names. These really should be types, not strings.
 * But since these things are going over the wire, we should put them in one
 * place so that every place that refers to them agrees.
 */
object AttributeKeys {
  val cluster = "cluster"
  val source = "source"
  val kind = "kind"
  val experimentID = "experiment_id"
  val experimentGroup = "experiment_group"
  val units = "units"
  val edge = "edge"
}
