package com.kyle.leadhopper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class LeadHopperDataSurvivalTest {
    @Rule
    public ActivityScenarioRule<MainActivity> activityRule = new ActivityScenarioRule<>(MainActivity.class);

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private WebView webView;

    @Before
    public void setUp() throws Exception {
        activityRule.getScenario().onActivity(activity -> {
            View root = activity.findViewById(android.R.id.content);
            webView = findWebView(root);
        });
        assertNotNull("MainActivity must contain the production WebView", webView);
        waitForJs("return document.readyState==='complete' && !!window.__LH_STORAGE_READY__ && !!window.__LH_V19_DATA_SURVIVAL__;", 15_000);
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private String evalRaw(String body) throws Exception {
        AtomicReference<String> result = new AtomicReference<>("null");
        CountDownLatch latch = new CountDownLatch(1);
        String wrapped = "(function(){try{" + body + "}catch(e){return 'LH_TEST_ERROR:'+e.name+':'+e.message;}})();";
        instrumentation.runOnMainSync(() -> webView.evaluateJavascript(wrapped, value -> {
            result.set(value);
            latch.countDown();
        }));
        assertTrue("JavaScript evaluation timed out", latch.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    private String evalString(String body) throws Exception {
        String raw = evalRaw(body);
        if (raw == null || "null".equals(raw)) return null;
        return new JSONArray("[" + raw + "]").getString(0);
    }

    private void waitForJs(String body, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if ("true".equals(evalRaw(body))) return;
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for JS condition: " + body);
    }

    private String richFixtureScript(String name) {
        return "({" +
                "leads:[{id:'persist-lead',firstName:'" + name + "',lastName:'Sentinel',phone:'3175550199',email:'persist@example.com',address:'99 Save St',city:'Indianapolis',state:'IN',zip:'46201',disposition:'Callback',nextEligibleAt:null,calls:[{t:'2026-09-12T20:00:00.000Z'}],history:[{t:'2026-09-12T20:01:00.000Z',action:'test'}],notes:'durable note'}]," +
                "schedule:[{id:'persist-cb',type:'callback',leadId:'persist-lead',when:'2026-09-14T15:00:00.000Z',createdAt:'2026-09-12T20:02:00.000Z',done:false,notes:'call back'}]," +
                "callLog:[{id:'persist-call',t:'2026-09-12T20:00:00.000Z',leadId:'persist-lead',phone:'3175550199'}]," +
                "activityLog:[{id:'persist-act',t:'2026-09-12T20:02:00.000Z',leadId:'persist-lead',type:'Callback',detail:'must survive'}]," +
                "currentIndex:0,cursorLeadId:'persist-lead',holdLeadId:'persist-lead',overrideLeadId:null," +
                "advance:{ready:true,leadId:'persist-lead',disp:'Callback',t:'2026-09-12T20:02:00.000Z'}," +
                "prevStack:['older-lead'],noEnglish:[{leadId:'persist-lead',lang:'Spanish',notes:'test',t:'2026-09-12T19:00:00.000Z'}]," +
                "priorityLeadIds:['persist-lead'],customButtons:[{id:'custom-1',label:'Later',snoozeDays:3,enabled:true}]," +
                "suppression:{dnc:[{id:'dnc-1',phone:'3175550111',displayName:'Dnc Person',createdAt:'2026-09-12T18:00:00.000Z'}],wrongNumbers:[{id:'wn-1',key:'wrong|person|3175550222',phone:'3175550222',nameKey:'wrong|person',displayName:'Wrong Person',createdAt:'2026-09-12T18:30:00.000Z'}]}," +
                "settings:{cphGoal:20,notInterestedDays:45,unlockMinutes:30,appointmentSpacingMinutes:120}," +
                "callSession:{startedAt:'2026-09-12T19:30:00.000Z'},activeTab:'schedule',schemaVersion:13" +
                "})";
    }

    @Test
    public void bootstrapWriteIsBlockedUntilHydration() throws Exception {
        JSONObject status = new JSONObject(evalString("return JSON.stringify(v19GetPersistenceStatus());"));
        assertTrue(status.getBoolean("hydrated"));
        assertTrue("The historical V13 pre-load save should have been intercepted", status.getInt("blockedPreloadSaves") >= 1);
        assertTrue(status.getBoolean("hasSnapshot"));
    }

    @Test
    public void richPersistedStateSurvivesRealReload() throws Exception {
        String fixture = richFixtureScript("Persistence");
        assertEquals("seeded", evalString(
                "var s=" + fixture + ";state=s;localStorage.setItem(STORE_KEY,JSON.stringify(s));return 'seeded';"
        ));

        evalRaw("location.reload();return true;");
        waitForJs("return document.readyState==='complete' && !!window.__LH_STORAGE_READY__;", 15_000);

        String json = evalString(
                "return JSON.stringify({" +
                        "lead:state.leads[0]&&state.leads[0].id," +
                        "name:state.leads[0]&&state.leads[0].firstName," +
                        "note:state.leads[0]&&state.leads[0].notes," +
                        "schedule:state.schedule[0]&&state.schedule[0].id," +
                        "call:state.callLog[0]&&state.callLog[0].id," +
                        "activity:state.activityLog[0]&&state.activityLog[0].id," +
                        "cursor:state.cursorLeadId,hold:state.holdLeadId,tab:state.activeTab," +
                        "prev:state.prevStack[0],custom:state.customButtons[0]&&state.customButtons[0].id," +
                        "dnc:state.suppression.dnc[0]&&state.suppression.dnc[0].id," +
                        "wrong:state.suppression.wrongNumbers[0]&&state.suppression.wrongNumbers[0].id," +
                        "noEnglish:state.noEnglish[0]&&state.noEnglish[0].lang" +
                        "});"
        );
        JSONObject o = new JSONObject(json);
        assertEquals("persist-lead", o.getString("lead"));
        assertEquals("Persistence", o.getString("name"));
        assertEquals("durable note", o.getString("note"));
        assertEquals("persist-cb", o.getString("schedule"));
        assertEquals("persist-call", o.getString("call"));
        assertEquals("persist-act", o.getString("activity"));
        assertEquals("persist-lead", o.getString("cursor"));
        assertEquals("persist-lead", o.getString("hold"));
        assertEquals("schedule", o.getString("tab"));
        assertEquals("older-lead", o.getString("prev"));
        assertEquals("custom-1", o.getString("custom"));
        assertEquals("dnc-1", o.getString("dnc"));
        assertEquals("wn-1", o.getString("wrong"));
        assertEquals("Spanish", o.getString("noEnglish"));
    }

    @Test
    public void failedStorageWriteRollsBackToLastDurableSnapshot() throws Exception {
        String fixture = richFixtureScript("Durable");
        String json = evalString(
                "state=" + fixture + ";" +
                        "var firstSave=saveAll();" +
                        "var original=Storage.prototype.setItem;" +
                        "Storage.prototype.setItem=function(){throw new Error('forced quota failure')};" +
                        "state.leads[0].firstName='RAM ONLY';" +
                        "var failedSave=saveAll();" +
                        "Storage.prototype.setItem=original;" +
                        "var stored=JSON.parse(localStorage.getItem(STORE_KEY));" +
                        "return JSON.stringify({firstSave:firstSave,failedSave:failedSave,memory:state.leads[0].firstName,stored:stored.leads[0].firstName,lastSaveOk:window.__LH_LAST_SAVE_OK__});"
        );
        JSONObject o = new JSONObject(json);
        assertTrue(o.getBoolean("firstSave"));
        assertFalse(o.getBoolean("failedSave"));
        assertEquals("Durable", o.getString("memory"));
        assertEquals("Durable", o.getString("stored"));
        assertFalse(o.getBoolean("lastSaveOk"));
    }

    @Test
    public void fullBackupRoundTripsAllCriticalStateAndRejectsGarbage() throws Exception {
        String fixture = richFixtureScript("Backup");
        String json = evalString(
                "state=" + fixture + ";saveAll();" +
                        "window.__capturedBackup=null;" +
                        "downloadText=function(filename,text,mime){window.__capturedBackup={filename:filename,text:text,mime:mime}};" +
                        "var exported=exportData();" +
                        "var env=JSON.parse(window.__capturedBackup.text);" +
                        "state.leads=[];state.schedule=[];state.callLog=[];state.activityLog=[];state.cursorLeadId=null;" +
                        "var restored=v19ApplyBackupText(window.__capturedBackup.text,false);" +
                        "var beforeBad=localStorage.getItem(STORE_KEY);var rejected=false;" +
                        "try{v19ApplyBackupText('{bad json',false)}catch(e){rejected=true}" +
                        "var afterBad=localStorage.getItem(STORE_KEY);" +
                        "return JSON.stringify({exported:exported,format:env.format,version:env.backupVersion,mime:window.__capturedBackup.mime,restored:restored,rejected:rejected,unchanged:beforeBad===afterBad,lead:state.leads[0]&&state.leads[0].id,note:state.leads[0]&&state.leads[0].notes,schedule:state.schedule[0]&&state.schedule[0].id,call:state.callLog[0]&&state.callLog[0].id,activity:state.activityLog[0]&&state.activityLog[0].id,custom:state.customButtons[0]&&state.customButtons[0].id,dnc:state.suppression.dnc[0]&&state.suppression.dnc[0].id,wrong:state.suppression.wrongNumbers[0]&&state.suppression.wrongNumbers[0].id,tab:state.activeTab});"
        );
        JSONObject o = new JSONObject(json);
        assertTrue(o.getBoolean("exported"));
        assertEquals("lead-hopper-full-backup", o.getString("format"));
        assertEquals(1, o.getInt("version"));
        assertEquals("application/json", o.getString("mime"));
        assertTrue(o.getBoolean("restored"));
        assertTrue(o.getBoolean("rejected"));
        assertTrue(o.getBoolean("unchanged"));
        assertEquals("persist-lead", o.getString("lead"));
        assertEquals("durable note", o.getString("note"));
        assertEquals("persist-cb", o.getString("schedule"));
        assertEquals("persist-call", o.getString("call"));
        assertEquals("persist-act", o.getString("activity"));
        assertEquals("custom-1", o.getString("custom"));
        assertEquals("dnc-1", o.getString("dnc"));
        assertEquals("wn-1", o.getString("wrong"));
        assertEquals("schedule", o.getString("tab"));
    }
}
