package io.kaitai.struct.format

import io.kaitai.struct.formats.JavaKSYParser
import org.scalatest.FunSpec
import org.scalatest.Matchers._

class AttrSpec$Test extends FunSpec {
  def tryOne(yamlStr: String) = AttrSpec.parseContentSpec(JavaKSYParser.stringToYaml(yamlStr), List("test"))

  describe("AttrSpec.parseContentSpec") {
    it ("parses single ASCII string") {
      tryOne("\"foo\"") should be(Array[Byte](102, 111, 111))
    }

    it ("parses single UTF-8 string") {
      tryOne("\"彼女\"") should be(Array(0xe5, 0xbd, 0xbc, 0xe5, 0xa5, 0xb3).map(x => x.asInstanceOf[Byte]))
    }

    it ("parses JSON array of decimal integers") {
      tryOne("[1, 55, 111, 102]") should be(Array[Byte](1, 55, 111, 102))
    }

    it ("parses JSON array of hex integers") {
      tryOne("[0x1, 0x55, 0xff]") should be(Array[Byte](0x1, 0x55, 0xff.asInstanceOf[Byte]))
    }

    it ("parses YAML array of decimal integers") {
      tryOne("- 1\n- 55\n- 111\n- 102") should be(Array[Byte](1, 55, 111, 102))
    }

    it ("parses YAML array of hex integers") {
      tryOne("- 0x1\n- 0x37\n- 0x6f\n- 0x66") should be(Array[Byte](0x1, 0x37, 0x6f, 0x66))
    }

    it ("parses JSON array of decimal integers + ASCII string") {
      tryOne("[1, 55, 'foo', 3]") should be(Array[Byte](1, 55, 102, 111, 111, 3))
    }

    it ("parses JSON array of decimal integers + hex integers + UTF-8 string") {
      tryOne("[1, 0x55, '彼', 3]") should be(Array(1, 0x55, 0xe5, 0xbd, 0xbc, 3).map(x => x.asInstanceOf[Byte]))
    }

    it ("parses complex spec 1") {
      tryOne("[1, 0x55, '▒,3', 3]") should
        be(Array(1, 0x55, 0xe2, 0x96, 0x92, 0x2c, 0x33, 3).map(x => x.asInstanceOf[Byte]))
    }

    it ("parses complex spec 2") {
      tryOne("[foo, 0, A, 0xa, 42]") should
        be(Array(0x66, 0x6f, 0x6f, 0x00, 0x41, 0x0a, 0x2a).map(x => x.asInstanceOf[Byte]))
    }

    it ("fails to parse double") {
      the [YAMLParseException] thrownBy tryOne("1.234") should
        have message("/test: unable to parse fixed content: 1.234")
    }

    it ("fails to parse map") {
      the [YAMLParseException] thrownBy tryOne("foo: 123") should
        have message("/test: unable to parse fixed content: Map(foo -> 123)")
    }

    it ("fails to parse bogus array element") {
      the [YAMLParseException] thrownBy tryOne("[1, 2, [3]]") should
        have message("/test/2: unable to parse fixed content in array: List(3)")
    }
  }
}
