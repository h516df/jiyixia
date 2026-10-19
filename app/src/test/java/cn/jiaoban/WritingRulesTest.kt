package cn.jiaoban

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WritingRulesTest {
    @Test fun tagsHandleChineseNewlineDuplicatesAndPunctuation(){assertEquals(listOf("生活","读书","idea_2"),writingTags("随手记 #生活，\n#读书 #生活 #idea_2。"))}
    @Test fun urlFragmentsAreNotTags(){assertEquals(listOf("想法"),writingTags("https://example.com/#fragment https://example.com?q=#fragment #想法"))}
    @Test fun hierarchyIsNotPartiallyRecognized(){assertEquals(emptyList<String>(),writingTags("#父/子"))}
    @Test fun plainAndEmptyHaveNoTags(){assertTrue(writingTags("普通文字\n#").isEmpty())}
    @Test fun tagFilterIsExact(){val n=Task("1","#生活方式",createdAt=Instant.EPOCH,kind=EntryKind.NOTE);assertFalse(writingMatches(n,"","生活"));assertTrue(writingMatches(n,"","生活方式"))}
    @Test fun dailyViewIncludesOrdinaryNotesAndBackdatedDiary(){val n=Task("1","随手记",createdAt=Instant.parse("2026-10-19T23:00:00Z"),kind=EntryKind.NOTE);assertEquals(LocalDate.of(2026,10,20),writingDay(n,ZoneId.of("Asia/Shanghai")));assertEquals(LocalDate.of(2026,10,1),writingDay(n.copy(journalDate=LocalDate.of(2026,10,1))))}
    @Test fun reviewExcludesArchivedTasksAndToday(){val at=Instant.parse("2026-10-18T00:00:00Z");val n=Task("1","旧记录",createdAt=at,kind=EntryKind.NOTE);assertEquals(listOf(n),reviewEntries(listOf(n,n.copy(id="a",state=TaskState.VOID),n.copy(id="b",kind=EntryKind.TODO),n.copy(id="c",journalDate=LocalDate.of(2026,10,19))),LocalDate.of(2026,10,19),ZoneOffset.UTC))}
}
