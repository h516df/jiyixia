package cn.jiaoban.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class BackupPrivacyTest {
    private val domains=setOf("root","file","database","sharedpref","external","device_root","device_file","device_database","device_sharedpref")
    private fun xml(path:String)=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/$path"))
    @Test fun manifestDisablesAutomaticBackupAndReferencesExplicitRules(){
        val app=xml("AndroidManifest.xml").getElementsByTagName("application").item(0).attributes
        assertEquals("false",app.getNamedItem("android:allowBackup").nodeValue)
        assertEquals("@xml/backup_rules",app.getNamedItem("android:fullBackupContent").nodeValue)
        assertEquals("@xml/data_extraction_rules",app.getNamedItem("android:dataExtractionRules").nodeValue)
    }
    @Test fun cloudAndDeviceTransferExcludeEveryPrivateDomain(){
        val doc=xml("res/xml/data_extraction_rules.xml")
        for(section in listOf("cloud-backup","device-transfer")){
            val exclusions=(doc.getElementsByTagName(section).item(0) as org.w3c.dom.Element).getElementsByTagName("exclude")
            val actual=(0 until exclusions.length).map{val a=exclusions.item(it).attributes;assertEquals(".",a.getNamedItem("path").nodeValue);a.getNamedItem("domain").nodeValue}.toSet()
            assertEquals(domains,actual)
        }
        val legacy=xml("res/xml/backup_rules.xml").getElementsByTagName("exclude")
        assertEquals(domains,(0 until legacy.length).map{legacy.item(it).attributes.getNamedItem("domain").nodeValue}.toSet())
    }
}
