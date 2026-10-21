package com.heimish.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdaterFeedTest {
    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <id>tag:github.com,2008:Repository/1/v3.6-b257</id>
    <link rel="alternate" type="text/html" href="https://github.com/848848448/heimish-messages/releases/tag/v3.6-b257"/>
    <title>Message v3.6.257</title>
    <content type="html">&lt;p&gt;New&lt;/p&gt;
&lt;ul&gt;
&lt;li&gt;Calls go straight through &amp;amp; fast&lt;/li&gt;
&lt;/ul&gt;
&lt;p&gt;Fixed&lt;/p&gt;
&lt;ul&gt;
&lt;li&gt;The &amp;quot;No results&amp;quot; screen&lt;/li&gt;
&lt;/ul&gt;</content>
  </entry>
  <entry>
    <link rel="alternate" type="text/html" href="https://github.com/848848448/heimish-messages/releases/tag/v3.6-b256"/>
    <content type="html">&lt;p&gt;Fixed&lt;/p&gt;&lt;ul&gt;&lt;li&gt;One&lt;/li&gt;&lt;/ul&gt;</content>
  </entry>
</feed>"""

    @Test fun parsesBuildsAndNotes() {
        val r = Updater.parseFeed(feed)
        assertEquals(listOf(257, 256), r.map { it.first })
        assertEquals("New\n- Calls go straight through & fast\nFixed\n- The \"No results\" screen", r[0].second)
        assertEquals("Fixed\n- One", r[1].second)
    }
}
