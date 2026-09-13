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
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class LeadHopperPhase0ContractTest {
    @Rule
    public ActivityScenarioRule<MainActivity> activityRule = new ActivityScenarioRule<>(MainActivity.class);

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private WebView webView;

    @Before
    public void setUp() throws Exception {
        activityRule.getScenario().onActivity(activity -> webView = findWebView(activity.findViewById(android.R.id.content)));
        assertNotNull("MainActivity must contain the production WebView", webView);
        waitForJs("return document.readyState==='complete' && !!window.__LH_STORAGE_READY__ && !!window.__LH_PHASE0_CONTRACT_REPAIRS__;", 15_000);
    }

    @After
    public void leaveJournalSynchronized() throws Exception {
        String result = evalString(
                "if(!window.__LH_STORAGE_READY__||typeof state==='undefined'||!state)return 'not-ready';" +
                        "lastSnapshot=null;saveAll();" +
                        "var raw=localStorage.getItem(STORE_KEY);" +
                        "var recovered=JSON.parse(AndroidBridge.recoverSnapshot(raw));" +
                        "return recovered.ok&&recovered.state===raw?'synced':'diverged';"
        );
        assertEquals("Phase 0 tests must leave the native journal synchronized", "synced", result);
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
        assertTrue("JavaScript evaluation timed out", latch.await(12, TimeUnit.SECONDS));
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
            Thread.sleep(120);
        }
        throw new AssertionError("Timed out waiting for JS condition: " + body);
    }

    private String baseState(String leadsJson, String scheduleJson, String customJson) {
        return "state={" +
                "leads:" + leadsJson + ",schedule:" + scheduleJson + ",callLog:[],activityLog:[]," +
                "currentIndex:0,cursorLeadId:null,holdLeadId:null,overrideLeadId:null," +
                "advance:{ready:false,leadId:null,disp:null,t:null},prevStack:[],noEnglish:[],priorityLeadIds:[]," +
                "customButtons:" + customJson + ",suppression:{dnc:[],wrongNumbers:[]}," +
                "settings:{cphGoal:20,notInterestedDays:45,unlockMinutes:30,appointmentSpacingMinutes:120}," +
                "activeTab:'hopper',schemaVersion:13};lastSnapshot=null;saveAll();renderAll();";
    }

    @Test
    public void sharedPhoneGetsOneColdOpportunityAndReferenceSheetCannotSwitchPerson() throws Exception {
        String leads = "[" +
                "{id:'bob',firstName:'Bob',lastName:'Smith',phone:'(317) 555-0101',disposition:'New',calls:[],history:[],active:true}," +
                "{id:'unique',firstName:'Uma',lastName:'Unique',phone:'3175550199',disposition:'New',calls:[],history:[],active:true}," +
                "{id:'mary',firstName:'Mary',lastName:'Smith',phone:'1-317-555-0101',disposition:'New',calls:[],history:[],active:true}," +
                "{id:'susan',firstName:'Susan',lastName:'Carter',phone:'3175550101',disposition:'New',calls:[],history:[],active:true}" +
                "]";
        String json = evalString(
                baseState(leads, "[]", "[]") +
                        "var cold=eligibleLeads().map(function(x){return x.id;});syncCursor();renderAll();" +
                        "var current=getCurrentLead();var badge=document.getElementById('phase0ClusterBadge');" +
                        "var before=current&&current.id;var sheetOk=phase0OpenCluster(current.phone,current.id);var after=getCurrentLead()&&getCurrentLead().id;" +
                        "return JSON.stringify({cold:cold,current:before,badge:badge&&badge.textContent,sheetOk:sheetOk,after:after});"
        );
        JSONObject o = new JSONObject(json);
        JSONArray cold = o.getJSONArray("cold");
        assertEquals("Three people sharing one number must contribute one ordinary cold opportunity plus the unique phone", 2, cold.length());
        assertEquals("Alphabetical primary person for the shared phone should be Susan Carter", "susan", cold.getString(0));
        assertEquals("susan", o.getString("current"));
        assertEquals("+2", o.getString("badge"));
        assertTrue("Opening the shared-number reference sheet must report identity stability", o.getBoolean("sheetOk"));
        assertEquals("Shared-number sheet must not switch the active person", "susan", o.getString("after"));
    }

    @Test
    public void dueCallbackKeepsItsExactPersonEvenOnSharedPhone() throws Exception {
        String leads = "[" +
                "{id:'bob',firstName:'Bob',lastName:'Smith',phone:'3175550101',disposition:'Callback',calls:[],history:[],active:true}," +
                "{id:'susan',firstName:'Susan',lastName:'Carter',phone:'3175550101',disposition:'New',calls:[],history:[],active:true}," +
                "{id:'unique',firstName:'Uma',lastName:'Unique',phone:'3175550199',disposition:'New',calls:[],history:[],active:true}" +
                "]";
        String schedule = "[{id:'due-bob',type:'callback',leadId:'bob',when:new Date(Date.now()-60000).toISOString(),createdAt:'2026-09-13T12:00:00.000Z',done:false}]";
        String json = evalString(
                baseState(leads, schedule, "[]") +
                        "var ids=eligibleLeads().map(function(x){return x.id;});return JSON.stringify(ids);"
        );
        JSONArray ids = new JSONArray(json);
        assertEquals(2, ids.length());
        assertEquals("A due callback is person-specific and must keep Bob as the scheduled owner", "bob", ids.getString(0));
        assertEquals("unique", ids.getString(1));
    }

    @Test
    public void customFutureSnoozeUsesGenericEligibilityRule() throws Exception {
        String leads = "[" +
                "{id:'A',firstName:'Alpha',lastName:'Able',phone:'3175550101',disposition:'New',calls:[{t:new Date().toISOString()}],history:[],active:true}," +
                "{id:'B',firstName:'Beta',lastName:'Baker',phone:'3175550102',disposition:'New',calls:[],history:[],active:true}" +
                "]";
        String custom = "[{id:'later',label:'Try Later',snoozeDays:3,enabled:true,createdAt:new Date().toISOString()}]";
        String json = evalString(
                baseState(leads, "[]", custom) +
                        "state.cursorLeadId='A';saveAll();renderAll();v13UseCustom('later');" +
                        "var a=state.leads.find(function(x){return x.id==='A';});" +
                        "return JSON.stringify({future:new Date(a.nextEligibleAt).getTime()>Date.now(),eligible:eligibleLeads().map(function(x){return x.id;})});"
        );
        JSONObject o = new JSONObject(json);
        assertTrue(o.getBoolean("future"));
        JSONArray eligible = o.getJSONArray("eligible");
        assertEquals(1, eligible.length());
        assertEquals("B", eligible.getString(0));
    }

    @Test
    public void replaceArchivesOmittedPersonAndKeepsTheirScheduledWorkVisible() throws Exception {
        String leads = "[{id:'old-person',firstName:'Old',lastName:'Person',phone:'3175550101',disposition:'Callback',calls:[],history:[{t:'2026-09-12T12:00:00.000Z',action:'note',value:'keep me'}],active:true}]";
        String schedule = "[{id:'old-callback',type:'callback',leadId:'old-person',when:new Date(Date.now()+3600000).toISOString(),createdAt:'2026-09-12T13:00:00.000Z',done:false,notes:'still matters'}]";
        String json = evalString(
                baseState(leads, schedule, "[]") +
                        "document.getElementById('importFormat').value='json';document.getElementById('importMode').value='replace';" +
                        "document.getElementById('importText').value=JSON.stringify([{firstName:'New',lastName:'Person',phone:'3175550202'}]);" +
                        "var oldConfirm=window.confirm;window.confirm=function(){return true;};doImport();window.confirm=oldConfirm;" +
                        "setTab('schedule',false);renderSchedule();" +
                        "var old=state.leads.find(function(x){return x.id==='old-person';});var scheduled=state.schedule.find(function(x){return x.id==='old-callback';});" +
                        "return JSON.stringify({oldExists:!!old,active:old&&old.active,history:old&&old.history&&old.history[0]&&old.history[0].value,linked:scheduled&&scheduled.leadId,marker:!!document.querySelector('.phase0-archived-schedule')});"
        );
        JSONObject o = new JSONObject(json);
        assertTrue("Replace must retain the omitted person as an archived record", o.getBoolean("oldExists"));
        assertFalse("Omitted person must leave the active calling roster", o.getBoolean("active"));
        assertEquals("keep me", o.getString("history"));
        assertEquals("old-person", o.getString("linked"));
        assertTrue("Open scheduled work for an archived person must be visibly surfaced", o.getBoolean("marker"));
    }

    @Test
    public void undoRestoresBusinessStateButRetainsReversedAudit() throws Exception {
        String leads = "[{id:'A',firstName:'Alpha',lastName:'Able',phone:'3175550101',disposition:'New',calls:[{t:new Date().toISOString()}],history:[],active:true}]";
        String json = evalString(
                baseState(leads, "[]", "[]") +
                        "state.cursorLeadId='A';saveAll();renderAll();applyDisposition('Not Interested');" +
                        "var afterDisposition=state.leads[0].disposition;var undoOk=restoreSnapshot();var lead=state.leads.find(function(x){return x.id==='A';});" +
                        "var originalActivity=state.activityLog.find(function(x){return x.type==='Disposition'&&x.detail==='Not Interested';});" +
                        "var undoActivity=state.activityLog.find(function(x){return x.type==='Undo';});" +
                        "var originalHistory=lead.history.find(function(x){return x.action==='disposition'&&x.value==='Not Interested';});" +
                        "var undoHistory=lead.history.find(function(x){return x.action==='undo';});" +
                        "return JSON.stringify({afterDisposition:afterDisposition,undoOk:undoOk,restored:lead.disposition,reversedActivity:originalActivity&&originalActivity.reversed,reversedHistory:originalHistory&&originalHistory.reversed,undoActivity:!!undoActivity,undoHistory:!!undoHistory,nextEligibleAt:lead.nextEligibleAt||null});"
        );
        JSONObject o = new JSONObject(json);
        assertEquals("Not Interested", o.getString("afterDisposition"));
        assertTrue(o.getBoolean("undoOk"));
        assertEquals("New", o.getString("restored"));
        assertTrue("Original activity event must remain and be marked reversed", o.getBoolean("reversedActivity"));
        assertTrue("Original lead-history event must remain and be marked reversed", o.getBoolean("reversedHistory"));
        assertTrue("Undo must append an activity audit event", o.getBoolean("undoActivity"));
        assertTrue("Undo must append a lead-history reversal event", o.getBoolean("undoHistory"));
        assertTrue("Undo must restore the pre-action eligibility state", o.isNull("nextEligibleAt"));
    }
}
