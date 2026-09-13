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
public class LeadHopperPhoneQueueTest {
    @Rule
    public ActivityScenarioRule<MainActivity> activityRule = new ActivityScenarioRule<>(MainActivity.class);

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private WebView webView;

    @Before
    public void setUp() throws Exception {
        activityRule.getScenario().onActivity(activity -> webView = findWebView(activity.findViewById(android.R.id.content)));
        assertNotNull("MainActivity must contain the production WebView", webView);
        waitForJs("return document.readyState==='complete' && !!window.__LH_STORAGE_READY__ && !!window.__LH_PHASE0_PHONE_QUEUE__;", 15_000);
    }

    @After
    public void leaveJournalSynchronized() throws Exception {
        JSONObject result = new JSONObject(evalString("return JSON.stringify(v19VerifyDurableSync());"));
        assertTrue("Phone-queue tests must leave native and WebView state synchronized: " + result, result.getBoolean("ok"));
        assertEquals("synced", result.getString("status"));
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

    private String seedSharedPhone(String customButtons) {
        return "state={" +
                "leads:[" +
                "{id:'susan',firstName:'Susan',lastName:'Carter',phone:'3175550101',disposition:'New',calls:[{t:new Date().toISOString()}],history:[],active:true}," +
                "{id:'unique',firstName:'Uma',lastName:'Unique',phone:'3175550199',disposition:'New',calls:[],history:[],active:true}," +
                "{id:'bob',firstName:'Bob',lastName:'Smith',phone:'(317) 555-0101',disposition:'New',calls:[],history:[],active:true}," +
                "{id:'mary',firstName:'Mary',lastName:'Smith',phone:'1-317-555-0101',disposition:'New',calls:[],history:[],active:true}" +
                "],schedule:[],callLog:[],activityLog:[],currentIndex:0,cursorLeadId:'susan',holdLeadId:null,overrideLeadId:null," +
                "advance:{ready:false,leadId:null,disp:null,t:null},prevStack:[],noEnglish:[],priorityLeadIds:[]," +
                "customButtons:" + customButtons + ",suppression:{dnc:[],wrongNumbers:[]},phoneQueue:{}," +
                "settings:{cphGoal:20,notInterestedDays:45,unlockMinutes:30,appointmentSpacingMinutes:120},activeTab:'hopper',schemaVersion:13};" +
                "lastSnapshot=null;saveAll();setTab('hopper',false);renderAll();";
    }

    @Test
    public void noAnswerMovesThePhoneEntryToTailWithoutExposingSiblings() throws Exception {
        JSONObject o = new JSONObject(evalString(
                seedSharedPhone("[]") +
                        "applyDisposition('No Answer');" +
                        "return JSON.stringify({current:getCurrentLead()&&getCurrentLead().id,eligible:eligibleLeads().map(function(x){return x.id;}),q:phase0PhoneQueueStatus('3175550101')});"
        ));
        assertEquals("The unique number should become current after the shared phone is tailed", "unique", o.getString("current"));
        JSONArray eligible = o.getJSONArray("eligible");
        assertEquals(2, eligible.length());
        assertEquals("unique", eligible.getString(0));
        assertEquals("susan", eligible.getString(1));
        assertEquals("No Answer must retain the worked person as the single phone-entry owner", "susan", o.getJSONObject("q").getString("ownerLeadId"));
        assertTrue("No Answer must move the shared phone behind the unique phone", o.getJSONObject("q").getDouble("order") > 1d);
        assertFalse(eligible.toString().contains("bob"));
        assertFalse(eligible.toString().contains("mary"));
    }

    @Test
    public void notInterestedAndCustomSnoozeSleepTheEntirePhoneEntry() throws Exception {
        JSONObject ni = new JSONObject(evalString(
                seedSharedPhone("[]") +
                        "applyDisposition('Not Interested');" +
                        "return JSON.stringify({eligible:eligibleLeads().map(function(x){return x.id;}),q:phase0PhoneQueueStatus('3175550101')});"
        ));
        JSONArray niEligible = ni.getJSONArray("eligible");
        assertEquals(1, niEligible.length());
        assertEquals("unique", niEligible.getString(0));
        assertTrue(ni.getJSONObject("q").getString("nextEligibleAt").length() > 10);

        JSONObject custom = new JSONObject(evalString(
                seedSharedPhone("[{id:'later',label:'Try Later',snoozeDays:3,enabled:true,createdAt:new Date().toISOString()}]") +
                        "v13UseCustom('later');" +
                        "return JSON.stringify({eligible:eligibleLeads().map(function(x){return x.id;}),q:phase0PhoneQueueStatus('3175550101')});"
        ));
        JSONArray customEligible = custom.getJSONArray("eligible");
        assertEquals(1, customEligible.length());
        assertEquals("unique", customEligible.getString(0));
        assertEquals("Try Later", custom.getJSONObject("q").getString("reason"));
    }

    @Test
    public void futureCallbackReservesPhoneAndDueCallbackReturnsExactOwner() throws Exception {
        JSONObject o = new JSONObject(evalString(
                seedSharedPhone("[]") +
                        "openCallbackModalFromLead(getCurrentLead());" +
                        "document.getElementById('cbWhen').value=toLocalDTInputValue(new Date(Date.now()+3600000));" +
                        "document.getElementById('cbNotes').value='shared phone callback';saveCallback();" +
                        "var futureEligible=eligibleLeads().map(function(x){return x.id;});" +
                        "var cb=state.schedule.find(function(x){return x.type==='callback'&&x.leadId==='susan'&&!x.done;});" +
                        "cb.when=new Date(Date.now()-60000).toISOString();saveAll();" +
                        "var dueEligible=eligibleLeads().map(function(x){return x.id;});" +
                        "return JSON.stringify({current:getCurrentLead()&&getCurrentLead().id,future:futureEligible,due:dueEligible,owner:phase0PhoneQueueStatus('3175550101').ownerLeadId});"
        ));
        assertEquals("Callback save must auto-advance away from the reserved phone", "unique", o.getString("current"));
        JSONArray future = o.getJSONArray("future");
        assertEquals(1, future.length());
        assertEquals("unique", future.getString(0));
        JSONArray due = o.getJSONArray("due");
        assertEquals(2, due.length());
        assertEquals("The deliberate due callback must return its exact person first", "susan", due.getString(0));
        assertEquals("unique", due.getString(1));
        assertEquals("susan", o.getString("owner"));
    }

    @Test
    public void appointmentRemovesTheSharedPhoneFromColdCirculation() throws Exception {
        JSONObject o = new JSONObject(evalString(
                seedSharedPhone("[]") +
                        "openApptModalFromLead(getCurrentLead());" +
                        "document.getElementById('apptWhen').value=toLocalDTInputValue(new Date(Date.now()+3*3600000));" +
                        "document.getElementById('apptNotes').value='quote appointment';saveAppointment();" +
                        "return JSON.stringify({eligible:eligibleLeads().map(function(x){return x.id;}),q:phase0PhoneQueueStatus('3175550101'),appt:state.schedule.some(function(x){return x.type==='appointment'&&x.leadId==='susan'&&!x.done;})});"
        ));
        assertTrue(o.getBoolean("appt"));
        JSONArray eligible = o.getJSONArray("eligible");
        assertEquals(1, eligible.length());
        assertEquals("unique", eligible.getString(0));
        assertTrue(o.getJSONObject("q").getBoolean("removedFromCold"));
    }

    @Test
    public void wrongNumberFallsBackToAnotherPersonButDncSuppressesTheWholePhone() throws Exception {
        JSONObject wrong = new JSONObject(evalString(
                seedSharedPhone("[]") +
                        "applyDisposition('Wrong Number');" +
                        "return JSON.stringify({eligible:eligibleLeads().map(function(x){return x.id;}),owner:phase0PhoneQueueStatus('3175550101').ownerLeadId,wrongCount:state.suppression.wrongNumbers.length,dncCount:state.suppression.dnc.length});"
        ));
        JSONArray wrongEligible = wrong.getJSONArray("eligible");
        assertEquals(2, wrongEligible.length());
        assertTrue(wrongEligible.toString().contains("bob"));
        assertFalse(wrongEligible.toString().contains("susan"));
        assertEquals("bob", wrong.getString("owner"));
        assertEquals(1, wrong.getInt("wrongCount"));
        assertEquals(0, wrong.getInt("dncCount"));

        JSONObject dnc = new JSONObject(evalString(
                seedSharedPhone("[]") +
                        "applyDisposition('Do Not Call');" +
                        "return JSON.stringify({eligible:eligibleLeads().map(function(x){return x.id;}),wrongCount:state.suppression.wrongNumbers.length,dncCount:state.suppression.dnc.length});"
        ));
        JSONArray dncEligible = dnc.getJSONArray("eligible");
        assertEquals(1, dncEligible.length());
        assertEquals("unique", dncEligible.getString(0));
        assertEquals(0, dnc.getInt("wrongCount"));
        assertEquals(1, dnc.getInt("dncCount"));
    }

    @Test
    public void importReusesSiblingPhoneEntryAndAppendsNewDistinctPhoneAtTail() throws Exception {
        JSONObject o = new JSONObject(evalString(
                seedSharedPhone("[]") +
                        "var initialShared=phase0PhoneQueueStatus('3175550101').order;var initialUnique=phase0PhoneQueueStatus('3175550199').order;" +
                        "document.getElementById('importFormat').value='json';document.getElementById('importMode').value='merge';" +
                        "document.getElementById('importText').value=JSON.stringify([" +
                        "{firstName:'Zelda',lastName:'Smith',phone:'3175550101'}," +
                        "{firstName:'Nina',lastName:'Newphone',phone:'3175550888'}]);" +
                        "var oldConfirm=window.confirm;window.confirm=function(){return true;};doImport();window.confirm=oldConfirm;" +
                        "var afterShared=phase0PhoneQueueStatus('3175550101');var afterUnique=phase0PhoneQueueStatus('3175550199');var added=phase0PhoneQueueStatus('3175550888');" +
                        "return JSON.stringify({initialShared:initialShared,initialUnique:initialUnique,afterShared:afterShared.order,afterUnique:afterUnique.order,added:added.order,queueKeys:Object.keys(state.phoneQueue).length,eligible:eligibleLeads().map(function(x){return cleanPhone(x.phone);})});"
        ));
        assertEquals(o.getDouble("initialShared"), o.getDouble("afterShared"), 0d);
        assertEquals(o.getDouble("initialUnique"), o.getDouble("afterUnique"), 0d);
        assertTrue("A genuinely new phone must join behind all existing phone entries", o.getDouble("added") > o.getDouble("afterUnique"));
        assertEquals("The imported sibling must reuse the existing shared-phone entry", 3, o.getInt("queueKeys"));
        JSONArray eligible = o.getJSONArray("eligible");
        int sharedCount = 0;
        for (int i = 0; i < eligible.length(); i++) if ("3175550101".equals(eligible.getString(i))) sharedCount++;
        assertEquals("Importing a sibling must not create another ordinary cold opportunity", 1, sharedCount);
    }
}
