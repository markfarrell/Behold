package edu.crea

import scala.collection.JavaConverters._
import scala.collection.Iterator
import scala.collection.immutable.{
  List, Set, Stack
}

import edu.berkeley.nlp.syntax.Tree

import java.io.File
import java.io.FileOutputStream

import org.slf4j.{Logger, LoggerFactory}
import edu.berkeley.nlp.syntax.Trees.PennTreeRenderer

import org.gephi.graph.api.{ Graph, Node, Edge, GraphFactory, GraphModel }

import it.uniroma1.dis.wsngroup.gexf4j.core.impl.StaxGraphWriter

import TreeConversions._

/**
  * A one-state pushdown automaton; equivalent to a finite automaton.
  * Accepts linguistic trees as input. Builds nodes and edges on the graph.
  * @param model The [GraphModel] containing a graph.
  * @author Mark Farrell
 **/
class Compiler(model : GraphModel, verbose : Boolean = false) {

  private[this] var logger : Logger = LoggerFactory.getLogger(classOf[Compiler])

  private[this] var nodeStack : Stack[Node] = Stack.empty[Node]

  def apply(tree : LinguisticTree) : GraphModel = {

    val (_, edges) = compile(tree)

    def edgeToString(edge : Edge) : String = {
      val label = edge.getEdgeData.getLabel
      val sourceLabel = edge.getSource.getNodeData.getLabel
      val targetLabel = edge.getTarget.getNodeData.getLabel
      s"${label}(${sourceLabel}, ${targetLabel})"
    }

    if(verbose) {
      logger.debug(s"""Propositions:\n\t${edges.map(edgeToString).mkString("\n\t")}""")
    }

    reset()
    model

  }

  object PredicateArgument {

    private [this] def nounAdjunctRules = {
      Set(("NN", "NNS"), ("NN", "NN"), ("NN", "NNPS"),
        ("NNP", "NNS"), ("NNP", "NN"), ("NNP", "NNPS"),
        ("@NP", "NNS"), ("@NP", "NN"), ("@NP", "NNPS"))
    }

    def unapply(tree : LinguisticTree) : Boolean = {

      tree.getLabel match {
        case "@NP" | "NP" if tree.existsBinaryRules(nounAdjunctRules) => true
        case "NN" | "NNS" | "NNP" | "NNPS" => true
        case _ => false
      }

    }

  }

  object MonovalentPredicate {

    def unapply(tree : LinguisticTree) : Boolean = {

      tree.getLabel match {
        case "VB" | "VBD" | "VBZ" | "VBP" | "VBG" | "VBN" => true
        case _ => false
      }

    }

  }

  object DivalentPredicate {

    private[this] def divalentRules = {
      Set(("VB", "S"), ("VB", "NP"), ("VB", "PP"), ("VB", "SBAR"),
        ("VBD", "S"), ("VBD", "NP"), ("VBD", "PP"), ("VBD", "SBAR"),
        ("VBP", "S"), ("VBP", "NP"), ("VBP", "PP"), ("VBP", "SBAR"),
        ("VBG", "S"), ("VBG", "NP"), ("VBG", "PP"), ("VBG", "SBAR"),
        ("VBN", "S"), ("VBN", "NP"), ("VBN", "PP"), ("VBN", "SBAR"),
        ("@VP", "PP"))
    }

    def unapply(tree : LinguisticTree) : Option[Tuple2[LinguisticTree, LinguisticTree]] = {

      tree.getLabel match {
        case "@VP" | "VP" | "PP" => tree.findBinaryRules(divalentRules)
        case _ => None
      }

    }

  }

  object TrivalentPredicate {

    private[this] def trivalentRules = Set(("@VP", "NP"))

    def unapply(tree : LinguisticTree) : Option[Tuple2[LinguisticTree, LinguisticTree]] = {
      tree.getLabel match {
        case "@VP" | "VP" => tree.findBinaryRules(trivalentRules)
        case _ => None
      }
    }

  }

  object NonfiniteVerbPhrase {

    private [this] def nonfiniteVerbRules = {
      Set(("VBZ", "VP"), ("VB", "VP"),
        ("VBD", "VP"), ("VBP", "VP"),
        ("VBG", "VP"), ("VBN", "VP"),
        ("TO", "VP"), ("MD", "VP"))
    }

    def unapply(tree : LinguisticTree) : Option[Tuple2[LinguisticTree, LinguisticTree]] = {

      tree.getLabel match {
        case "@VP" | "VP" => tree.findBinaryRules(nonfiniteVerbRules)
        case _ => None
      }

    }

  }

  object NounPhraseWithPreposition {

    private [this] def nounPhraseWithPrepositionRules = {
      Set(("NP", "PP"), ("@NP", "PP"))
    }

    def unapply(tree : LinguisticTree) : Option[Tuple2[LinguisticTree, LinguisticTree]] = tree.getLabel match {
      case "@NP" | "NP" => {
        tree.findBinaryRules(nounPhraseWithPrepositionRules)
      }
      case _ => None
    }

  }

  object IgnoredConstituent {

    def unapply(tree : LinguisticTree) : Boolean = {

      tree.getLabel match {
        case "ADVP" | "X" | "@X" | "NX" | "@NX" | "DT" | "JJ" | "JJS" | "JJR" | "-LRB-" | "-RRB-" | "PRN" => true
        case _ => false
      }

    }

  }

  object NounVerbDeclarativeClause {

    private[this] def nounVerbRules = Set(("NP", "VP"), ("@S", "VP"))

    def unapply(tree : LinguisticTree) : Option[Tuple2[LinguisticTree, LinguisticTree]] = tree.getLabel match {
      case "@S" | "S" | "NP" => tree.findBinaryRules(nounVerbRules)
      case _ => None
    }

  }

  private[this] def compile(tree : LinguisticTree, lsts : (List[Node], List[Edge]) = (Nil, Nil)) : (List[Node], List[Edge]) = {

    val (nodes, edges) = lsts

    val children = tree.getChildren.asScala.toList

    tree match {
      case TrivalentPredicate(left, right) => {
        compile(left, compile(right, compile(left, lsts)))
      }
      case DivalentPredicate(left, right) => {

        val sourceOption = nodes.headOption

        val (targets, rightEdges) = compile(right, lsts)

        val label = left.terminalValue

        var newEdges : List[Edge] = Nil

        for {
          source <- sourceOption
          target <- targets
        } {
          newEdges ::= CreateEdge(source, target, label)
        }

        (targets, newEdges ++ rightEdges)

      }
      case MonovalentPredicate() => {

        val label = tree.terminalValue

        var newEdges : List[Edge] = Nil

        for {
          source <- nodes
        } {
          newEdges ::= CreateEdge(source, source, label)
        }

        (Nil, newEdges)

      }
      case PredicateArgument() => {

        CreateNode(tree.terminalValue) match {
          case Some(node) => (node :: Nil, Nil)
          case None => (Nil, Nil)
        }

      }
      case NounVerbDeclarativeClause(left, right) => {

        val (leftNodes, leftEdges) = compile(left)

        val(addedNodes, addedEdges) = {
          val (a, b) = leftNodes.map(node => compile(right, (node :: Nil, edges))).reverse.unzip
          (a.flatten, b.flatten)
        }

        (addedNodes ++ leftNodes, addedEdges ++ leftEdges)

      }
      case NounPhraseWithPreposition(left, right) => {

        val leftLsts = compile(left, lsts)
        val rightLsts = compile(right, lsts)

        val (targets, leftEdges) = leftLsts
        val (sources, rightEdges) = rightLsts
        val label = "has"

        var newEdges : List[Edge] = Nil

        for {
          source <- sources
          target <- targets
        } {
          newEdges ::= CreateEdge(source, target, label)
        }

        (sources, newEdges ++ rightEdges ++ leftEdges)

      }
      case NonfiniteVerbPhrase(_, right) => compile(right, lsts)
      case IgnoredConstituent() => (Nil, Nil)
      case _ => {

        var newNodes = List.empty[Node]
        var newEdges = List.empty[Edge]

        for((nodeLst, edgeLst) <- children.view.map(child => compile(child, lsts))) {
          newNodes ++= nodeLst
          newEdges ++= edgeLst
        }

        (newNodes, newEdges)

      }
    }

  }

  /**
    * Puts the automaton in its initial state.
   **/
  private[this] def reset() : Unit = {

    nodeStack = Stack.empty[Node]

  }

  private[this] object CreateNode {

    def apply(label : String) : Option[Node] = label match {
      case "" => None
      case _ => {

        val topic = Option(model.getGraph.getNode(label)) match {
          case Some(topic) => topic
          case None => {

            val topic = model.factory.newNode(label)

            topic.getNodeData.setLabel(label)
            topic.getNodeData.setColor(0.5f, 0.5f, 0.5f)

            model.getGraph.addNode(topic)

            topic
          }
        }

        Some(topic)

      }

    }

  }

  private[this] object CreateEdge {

    def apply(source : Node, target : Node, label : String) : Edge = {

      val edge = model.factory.newEdge(source, target)

      edge.getEdgeData.setColor(0.5f, 0.5f, 0.5f)
      edge.getEdgeData.setLabel(label)

      model.getGraph.addEdge(edge)

      edge

    }
  }

}

/**
  * Compiles a graph from the text provided to STDIN, saving it to a GEXF file.
 **/
object Compiler {

  import java.io.ByteArrayInputStream
  import java.io.InputStream

  /**
    * Command line options for the tool.
    **/
  case class Config(
    file : File = null,
    verbose : Boolean = false
  )

  /**
    * Set values for command line options. Specify
    * usage of the tool.
   **/
  val parser = new scopt.OptionParser[Config]("Compiler") {

    head("""Reads a block of text from STDIN. Compiles text into a text-network.""")

    opt[File]('f', "file").action {
      (x, c) => c.copy(file = x)
    }.text("file is a (GEXF) file property")

    opt[Unit]('v', "verbose").action {
      (_, c) => c.copy(verbose = true)
    }.text("flag for printing debug messages")

    help("help").text("Prints this help message.")

  }

  def apply(sentence : String, verbose : Boolean = true) : GraphModel = Compiler(new ByteArrayInputStream(sentence.getBytes), verbose)

  def apply(inputStream : InputStream, verbose : Boolean) : GraphModel = {

    val parser = new MemoizedParser(verbose = verbose)

    val model = CreateGraphModel()

    val compiler = new Compiler(model, verbose)
    val sentenceTrees = Blurb.tokens(inputStream).map(parser.apply)

    sentenceTrees.foreach(compiler.apply)

    model
  }

  def main(args : Array[String]) : Unit = {

    parser.parse(args, Config()) map {
      cfg => {

        if(cfg.file != null) {

          val fs : FileOutputStream = new FileOutputStream(cfg.file)

          (new StaxGraphWriter).writeToStream(ToGexf(Compiler(System.in, cfg.verbose)), fs, "UTF-8")

          fs.close()

        }


      }

    }

  }

}

