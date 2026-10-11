package cn.jiaoban.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonParserTest {

    @Test
    fun surnamePlusTitle() {
        assertEquals("王总", PersonParser.parse("王总说明天要周报"))
        assertEquals("李姐", PersonParser.parse("李姐让订会议室"))
        assertEquals("张经理", PersonParser.parse("张经理要求周五交"))
        assertEquals("张工", PersonParser.parse("张工说数据不对"))
    }

    @Test
    fun threeCharacterName() {
        assertEquals("张小明", PersonParser.parse("张小明说客户催了"))
    }

    @Test
    fun standaloneTitlesAtTheStart() {
        assertEquals("老板", PersonParser.parse("老板让我加班"))
        assertEquals("客户", PersonParser.parse("客户要改价格"))
    }

    @Test
    fun personMentionedInTheBodyIsNotTheAssigner() {
        assertEquals("", PersonParser.parse("把周报发给客户"))
        assertEquals("", PersonParser.parse("把材料交给王总"))
    }

    @Test
    fun noPersonMeansEmpty() {
        assertEquals("", PersonParser.parse(""))
        assertEquals("", PersonParser.parse("把周报发给客户"))
    }

    @Test
    fun knownNamesWin() {
        assertEquals("王总", PersonParser.parse("这个事王总提过", listOf("王总")))
        // 认识的名字出现在句子里就直接用，不要求它后面跟着"说/让/要"
        assertEquals("李姐", PersonParser.parse("李姐那边的事", listOf("李姐")))
        assertEquals("", PersonParser.parse("订会议室的事", listOf("李姐")))
    }
}
