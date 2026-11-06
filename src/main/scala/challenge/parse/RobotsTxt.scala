package challenge.parse

import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

/** Uma regra Allow/Disallow. `pattern` aceita `*` (qualquer sequencia) e `$` (fim) no final. */
final case class RobotsRule(allow: Boolean, pattern: String):
  private val regex: Regex =
    val anchored = pattern.endsWith("$")
    val body     = if anchored then pattern.dropRight(1) else pattern
    val re       = body.split("\\*", -1).map(Regex.quote).mkString(".*")
    (if anchored then re else re + ".*").r

  def matches(path: String): Boolean = regex.pattern.matcher(path).matches()

/** Regras aplicaveis ao nosso User-Agent. Segue o RFC 9309: vence a regra mais longa; em empate, Allow. */
final case class RobotsRules(rules: List[RobotsRule]):
  def isAllowed(pathAndQuery: String): Boolean =
    val hits = rules.filter(_.matches(pathAndQuery))
    if hits.isEmpty then true
    else
      val longest = hits.map(_.pattern.length).max
      hits.filter(_.pattern.length == longest).exists(_.allow)

object RobotsRules:
  val allowAll: RobotsRules = RobotsRules(Nil)

/** Parser puro de robots.txt: sem rede, testavel com fixture. */
object RobotsTxt:

  private final case class Group(agents: List[String], rules: List[RobotsRule])

  def parse(body: String, userAgent: String): RobotsRules =
    // Token do produto: "oystr-bot/0.1 (contato)" -> "oystr-bot"
    val token = userAgent.takeWhile(c => c != '/' && !c.isWhitespace).toLowerCase

    val groups   = ListBuffer.empty[Group]
    var agents   = ListBuffer.empty[String]
    var rules    = ListBuffer.empty[RobotsRule]
    var inRules  = false

    def flush(): Unit =
      if agents.nonEmpty then groups += Group(agents.toList, rules.toList)
      agents = ListBuffer.empty
      rules = ListBuffer.empty
      inRules = false

    body.linesIterator.foreach { raw =>
      val line = raw.takeWhile(_ != '#').trim
      line.indexOf(':') match
        case -1 => ()
        case i =>
          val field = line.take(i).trim.toLowerCase
          val value = line.drop(i + 1).trim
          field match
            case "user-agent" =>
              if inRules then flush()
              agents += value.toLowerCase
            case "allow" | "disallow" =>
              inRules = true
              // "Disallow:" vazio significa "nada bloqueado": nao gera regra.
              if value.nonEmpty then rules += RobotsRule(field == "allow", value)
            case _ => ()
    }
    flush()

    val specific = groups.filter(_.agents.exists(a => a != "*" && token.contains(a)))
    val chosen   = if specific.nonEmpty then specific else groups.filter(_.agents.contains("*"))
    RobotsRules(chosen.flatMap(_.rules).toList)
