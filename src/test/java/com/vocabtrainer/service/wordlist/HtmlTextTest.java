package com.vocabtrainer.service.wordlist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlTextTest {
    @Test
    void lineBreaksDivsAndParagraphsSeparateMeanings() {
        assertEquals("减弱; 减少", HtmlText.toText("减弱<br>减少", true));
        assertEquals("减弱; 减少", HtmlText.toText("减弱<BR/>减少<br />", true));
        assertEquals("减弱; 减少; 平息", HtmlText.toText("<div>减弱</div><div>减少</div><p>平息</p>", true));
        assertEquals("减弱; 减少", HtmlText.toText("<ul><li>减弱</li><li>减少</li></ul>", true));
        assertEquals("减弱; 减少", HtmlText.toText("减弱<br><br>\n减少", true));
        assertEquals("He refused, again.", HtmlText.toText("He refused,<br>again.", true));
        assertEquals("减弱； 减少", HtmlText.toText("减弱；<div>减少</div>", true));
    }

    @Test
    void otherTagsAreDroppedAndEntitiesUnescaped() {
        assertEquals("obdurate", HtmlText.toText("<b><font color=\"#ff0000\">obdurate</font></b>", true));
        assertEquals("salt & pepper < \"spice\" >", HtmlText.toText("salt &amp; pepper &lt; &quot;spice&quot; &gt;", true));
        assertEquals("a b", HtmlText.toText("a&nbsp;b", true));
        assertEquals("café — naïve", HtmlText.toText("caf&eacute; &mdash; na&#239;ve", true));
        assertEquals("你", HtmlText.toText("&#x4F60;", true));
        assertEquals("&unknown; stays", HtmlText.toText("&unknown; stays", true));
        assertEquals("a < b and 3 <4", HtmlText.toText("a < b and 3 <4", true));
        assertEquals("kept", HtmlText.toText("<!-- note -->kept", true));
    }

    @Test
    void styleSheetsAndScriptsAreDroppedWithTheirText() {
        assertEquals("abate", HtmlText.toText("<style>.card { font-family: arial; }</style>abate", true));
        assertEquals("减弱; 减少", HtmlText.toText("减弱<SCRIPT type=\"text/javascript\">\nalert(1);\n</script ><br>减少", true));
        assertTrue(HtmlText.looksLikeHtml("减弱<script>alert(1)</script>"));
    }

    @Test
    void soundAndImageReferencesAreDroppedInEveryMode() {
        assertEquals("abate", HtmlText.toText("abate[sound:abate_us.mp3]", true));
        assertEquals("abate", HtmlText.toText("[sound:abate.mp3] abate", false));
        assertEquals("减弱", HtmlText.toText("减弱<img src=\"abate.jpg\">", true));
        assertEquals("减弱", HtmlText.toText("<IMG SRC='a.png' />减弱", false));
        assertEquals("", HtmlText.toText("[sound:only.mp3]", true));
    }

    @Test
    void plainTextIsLeftAloneExceptForMediaReferences() {
        assertEquals("salt &amp; <b>pepper</b>", HtmlText.toText(" salt &amp; <b>pepper</b> ", false));
        assertEquals("", HtmlText.toText(null, true));
    }

    @Test
    void htmlIsRecognisedByTheTagsAndEntitiesAnkiWrites() {
        assertTrue(HtmlText.looksLikeHtml("减弱<br>减少"));
        assertTrue(HtmlText.looksLikeHtml("<div>减弱</div>"));
        assertTrue(HtmlText.looksLikeHtml("a&nbsp;b"));
        assertFalse(HtmlText.looksLikeHtml("减弱; 减少"));
        assertFalse(HtmlText.looksLikeHtml("a < b & c > d"));
        assertFalse(HtmlText.looksLikeHtml("<custom>"));
        assertFalse(HtmlText.looksLikeHtml(null));
    }

    @Test
    void escapedTextReadsBackUnchanged() {
        String text = "Tom & Jerry say \"<hi>\"; 你好";
        assertEquals("Tom &amp; Jerry say &quot;&lt;hi&gt;&quot;; 你好", HtmlText.escape(text));
        assertEquals(text, HtmlText.toText(HtmlText.escape(text), true));
    }
}
