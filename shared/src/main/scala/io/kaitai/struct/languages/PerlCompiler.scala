package io.kaitai.struct.languages

import io.kaitai.struct.LanguageOutputWriter
import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.exprlang.Ast.expr
import io.kaitai.struct.exprlang.DataType.{UserType, _}
import io.kaitai.struct.format._
import io.kaitai.struct.languages.components.{NoNeedForFullClassPath, _}
import io.kaitai.struct.translators.{BaseTranslator, PerlTranslator, TypeProvider}

class PerlCompiler(verbose: Boolean, out: LanguageOutputWriter)
  extends LanguageCompiler(verbose, out)
    with UniversalFooter
    with UpperCamelCaseClasses
    with AllocateIOLocalVar
    with EveryReadIsExpression
    with NoNeedForFullClassPath {

  import PerlCompiler._

  override def getStatic: LanguageCompilerStatic = PerlCompiler

  override def universalFooter: Unit = {
    out.dec
    out.puts("}")
  }

  override def fileHeader(topClassName: String): Unit = {
    out.puts(s"# $headerComment")
    out.puts
    out.puts("use strict;")
    out.puts("use warnings;")
    out.puts(s"use $kstructName;")
    out.puts(s"use $kstreamName;")
  }

  override def fileFooter(topClassName: String): Unit = {
    out.puts
    out.puts("1;")
  }

  override def classHeader(name: String): Unit = {
    out.puts
    out.puts("########################################################################")
    out.puts(s"package ${type2class(name)};")
    out.puts
    out.puts(s"our @ISA = '$kstructName';")
    out.puts
    out.puts("sub from_file {")
    out.inc
    out.puts("my $class = shift;")
    out.puts("my $filename = shift;")
    out.puts("my $fd;")
    out.puts
    out.puts("open($fd, '<', $filename) or return undef;")
    out.puts("binmode($fd);")
    out.puts("return new($class, Kaitai::Stream->new($fd));")
    universalFooter
  }

  override def classFooter(name: String): Unit = {}

  override def classConstructorHeader(name: String, parentClassName: String, rootClassName: String): Unit = {
    out.puts
    out.puts("sub new {")
    out.inc
    out.puts("my $class = shift;")
    out.puts("my $_io = shift;")
    out.puts("my $_parent = shift;")
    out.puts("my $_root = shift;")
    out.puts("my $self = Kaitai::Struct->new($_io);")
    out.puts
    out.puts("bless $self, $class;")
    out.puts(s"${privateMemberName(ParentIdentifier)} = $$_parent;")
    out.puts(s"${privateMemberName(RootIdentifier)} = $$_root || $$self;")
    out.puts
  }

  override def classConstructorFooter(): Unit = {
    out.puts
    out.puts("return $self;")
    universalFooter
  }

  override def attributeDeclaration(attrName: Identifier, attrType: BaseType, condSpec: ConditionalSpec): Unit = {}

  override def attributeReader(attrName: Identifier, attrType: BaseType): Unit = {}

  override def attrFixedContentsParse(attrName: Identifier, contents: Array[Byte]): Unit = {
    out.puts(s"${privateMemberName(attrName)} = $normalIO->ensure_fixed_contents(${contents.length}, [${contents.map(x => x.toInt & 0xff).mkString(", ")}])")
  }

  override def attrProcess(proc: ProcessExpr, varSrc: Identifier, varDest: Identifier): Unit = {
    val srcName = privateMemberName(varSrc)
    val destName = privateMemberName(varDest)

    out.puts(proc match {
      case ProcessXor(xorValue) =>
        val procName = translator.detectType(xorValue) match {
          case _: IntType => "process_xor_one"
          case _: BytesType => "process_xor_many"
        }
        s"$destName = $kstreamName::$procName($srcName, ${expression(xorValue)});"
      case ProcessZlib =>
        s"$destName = $kstreamName::process_zlib($srcName);"
      case ProcessRotate(isLeft, rotValue) =>
        val expr = if (isLeft) {
          expression(rotValue)
        } else {
          s"8 - (${expression(rotValue)})"
        }
        s"$destName = $kstreamName::process_rotate_left($srcName, $expr, 1);"
    })
  }

  override def allocateIO(id: Identifier, rep: RepeatSpec): String = {
    val memberName = privateMemberName(id)

    val args = rep match {
      case RepeatEos => s"$memberName[-1]"
      case RepeatExpr(_) => s"$memberName[i]"
      case NoRepeat => s"$memberName"
    }

    val ioName = s"$$io_${idToStr(id)}"

    out.puts(s"my $ioName = $kstreamName->new($args);")
    ioName
  }

  override def useIO(ioEx: expr): String = {
    out.puts(s"my $$io = ${expression(ioEx)};")
    "$io"
  }

  override def pushPos(io: String): Unit =
    out.puts(s"my $$_pos = $io->pos();")

  override def seek(io: String, pos: Ast.expr): Unit =
    out.puts(s"$io->seek(${expression(pos)});")

  override def popPos(io: String): Unit =
    out.puts(s"$io->seek($$_pos);")

  override def condIfHeader(expr: Ast.expr): Unit = {
    out.puts(s"if (${expression(expr)}) {")
    out.inc
  }

  override def condRepeatEosHeader(id: Identifier, io: String, dataType: BaseType, needRaw: Boolean): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = ();")
    out.puts(s"${privateMemberName(id)} = ();")
    out.puts(s"while (not $io->is_eof()) {")
    out.inc
  }

  override def handleAssignmentRepeatEos(id: Identifier, expr: String): Unit =
    out.puts(s"push @{${privateMemberName(id)}}, $expr;")

  override def condRepeatExprHeader(id: Identifier, io: String, dataType: BaseType, needRaw: Boolean, repeatExpr: expr): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = Array.new(${expression(repeatExpr)})")
    out.puts(s"${privateMemberName(id)} = Array.new(${expression(repeatExpr)})")
    out.puts(s"(${expression(repeatExpr)}).times { |i|")
    out.inc
  }

  override def handleAssignmentRepeatExpr(id: Identifier, expr: String): Unit =
    out.puts(s"${privateMemberName(id)}[i] = $expr;")

  override def condRepeatUntilHeader(id: Identifier, io: String, dataType: BaseType, needRaw: Boolean, untilExpr: expr): Unit = {
    if (needRaw)
      out.puts(s"${privateMemberName(RawIdentifier(id))} = ();")
    out.puts(s"${privateMemberName(id)} = ();")
    out.puts("do {")
    out.inc
  }

  override def handleAssignmentRepeatUntil(id: Identifier, expr: String): Unit = {
    out.puts(s"${translator.doName("_")} = $expr;")
    out.puts(s"push @{${privateMemberName(id)}}, ${translator.doName("_")};")
  }

  override def condRepeatUntilFooter(id: Identifier, io: String, dataType: BaseType, needRaw: Boolean, untilExpr: expr): Unit = {
    _currentIteratorType = Some(dataType)
    out.dec
    out.puts(s"} until (${expression(untilExpr)});")
  }

  override def handleAssignmentSimple(id: Identifier, expr: String): Unit =
    out.puts(s"${privateMemberName(id)} = $expr;")

  override def parseExpr(dataType: BaseType, io: String): String = {
    dataType match {
      case t: ReadableType =>
        s"$io->read_${t.apiCall}()"
      // Aw, crap, can't use interpolated strings here: https://issues.scala-lang.org/browse/SI-6476
      case StrByteLimitType(bs, encoding) =>
        s"$io->read_str_byte_limit(${expression(bs)}, " + '"' + encoding + "\")"
      case StrEosType(encoding) =>
        io + "->read_str_eos(\"" + encoding + "\")"
      case StrZType(encoding, terminator, include, consume, eosError) =>
        io + "->read_strz(\"" + encoding + '"' + s", $terminator, ${boolLiteral(include)}, ${boolLiteral(consume)}, ${boolLiteral(eosError)})"
      case EnumType(enumName, t) =>
        parseExpr(t, io)

      case BytesLimitType(size, _) =>
        s"$io->read_bytes(${expression(size)})"
      case BytesEosType(_) =>
        s"$io->read_bytes_full()"
      case t: UserType =>
        s"${type2class(t.name.last)}->new($io, $$self, ${privateMemberName(RootIdentifier)})"
    }
  }

  override def instanceHeader(className: String, instName: InstanceIdentifier, dataType: BaseType): Unit = {
    out.puts(s"sub ${instName.name} {")
    out.inc
    out.puts("my $self = shift;")
  }

  override def instanceCheckCacheAndReturn(instName: InstanceIdentifier): Unit = {
    out.puts(s"return ${privateMemberName(instName)} if (${privateMemberName(instName)});")
  }

  override def instanceReturn(instName: InstanceIdentifier): Unit = {
    out.puts(s"return ${privateMemberName(instName)};")
  }

  override def enumDeclaration(curClass: String, enumName: String, enumColl: Map[Long, String]): Unit = {
    out.puts

    enumColl.foreach { case (id, label) =>
      out.puts(s"our ${enumValue(enumName, label)} = $id;")
    }
  }

  def enumValue(enumName: String, enumLabel: String) = translator.doEnumByLabel(enumName, enumLabel)

  override def idToStr(id: Identifier): String = {
    id match {
      case NamedIdentifier(name) => name
      case si: SpecialIdentifier => si.name
      case RawIdentifier(inner) => s"_raw_${idToStr(inner)}"
      case InstanceIdentifier(name) => name
    }
  }

  override def privateMemberName(id: Identifier): String = s"$$self->{${idToStr(id)}}"

  override def publicMemberName(id: Identifier): String = idToStr(id)

  def boolLiteral(b: Boolean): String = translator.doBoolLiteral(b)
}

object PerlCompiler extends LanguageCompilerStatic
  with UpperCamelCaseClasses
  with StreamStructNames {
  override def getTranslator(tp: TypeProvider): BaseTranslator = new PerlTranslator(tp)
  override def outFileName(topClassName: String): String = s"${type2class(topClassName)}.pm"
  override def indent: String = "    "

  override def kstreamName: String = "Kaitai::Stream"
  override def kstructName: String = "Kaitai::Struct"
}
