package com.kyle.leadhopper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Environment;
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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class LeadHopperTortureTest {
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
        waitForJs("return document.readyState === 'complete' && !!window.__LH_STORAGE_READY__ && !!document.getElementById('btnCallNow');", 15_000);
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

    private boolean evalBoolean(String expression) throws Exception {
        return "true".equals(evalRaw("return !!(" + expression + ");"));
    }

    private void waitForJs(String body, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Throwable last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if ("true".equals(evalRaw(body))) return;
            } catch (Throwable t) {
                last = t;
            }
            Thread.sleep(120);
        }
        if (last != null) throw new AssertionError("Timed out waiting for JS condition", last);
        throw new AssertionError("Timed out waiting for JS condition: " + body);
    }

    private void seedTwoCallableLeads() throws Exception {
        String now = "new Date().toISOString()";
        String js =
                "state={" +
                "leads:[" +
                "{id:'A',firstName:'Alpha',lastName:'Able',phone:'3175550101',email:'a@example.com',address:'1 A St',city:'Indianapolis',state:'IN',zip:'46201',disposition:'New',calls:[{t:" + now + "}],history:[]}," +
                "{id:'B',firstName:'Beta',lastName:'Baker',phone:'3175550102',email:'b@example.com',address:'2 B St',city:'Indianapolis',state:'IN',zip:'46202',disposition:'New',calls:[],history:[]}" +
                "],schedule:[],callLog:[],activityLog:[],currentIndex:0,cursorLeadId:'A',holdLeadId:null,overrideLeadId:null," +
                "advance:{ready:false,leadId:null,disp:null,t:null},prevStack:[],noEnglish:[],priorityLeadIds:[],customButtons:[]," +
                "suppression:{dnc:[],wrongNumbers:[]},settings:{cphGoal:20,notInterestedDays:45,unlockMinutes:30,appointmentSpacingMinutes:120},activeTab:'hopper'};" +
                "saveAll();setTab('hopper',false);renderAll();return 'ok';";
        assertEquals("ok", evalString(js));
    }

    @Test
    public void productionPackageContainsEveryLockedRepairMarker() throws Exception {
        String json = evalString(
                "return JSON.stringify({" +
                        "hot:!!window.__LH_V13_HOTFIX__," +
                        "cb:!!window.__LH_V14_CALLBACK_AUTO_ADVANCE__," +
                        "hopper:!!window.__LH_V16_HOPPER_UI_REPAIR__," +
                        "notes:!!window.__LH_V17_UI_NOTES__," +
                        "reports:!!window.__LH_V18_ACTIVITY_REPORTS_REPAIR__" +
                        "});"
        );
        JSONObject o = new JSONObject(json);
        assertTrue(o.getBoolean("hot"));
        assertTrue(o.getBoolean("cb"));
        assertTrue(o.getBoolean("hopper"));
        assertTrue(o.getBoolean("notes"));
        assertTrue(o.getBoolean("reports"));
    }

    @Test
    public void hopperButtonsAreTrueFiftyFiftyAndEqualHeight() throws Exception {
        seedTwoCallableLeads();
        String json = evalString(
                "const r=id=>{const x=document.getElementById(id).getBoundingClientRect();return {x:x.x,y:x.y,w:x.width,h:x.height}};" +
                        "return JSON.stringify({call:r('btnCallNow'),ni:r('btnNI'),no:r('btnNoAnswer'),cb:r('btnCallback'),appt:r('btnAppt')});"
        );
        JSONObject o = new JSONObject(json);
        JSONObject call = o.getJSONObject("call");
        JSONObject ni = o.getJSONObject("ni");
        JSONObject no = o.getJSONObject("no");
        JSONObject cb = o.getJSONObject("cb");
        JSONObject appt = o.getJSONObject("appt");

        double eps = 1.1;
        assertTrue("Call Now must span the dock", call.getDouble("w") > ni.getDouble("w") * 1.8);
        assertTrue("NI and No Answer must be equal width", Math.abs(ni.getDouble("w") - no.getDouble("w")) <= eps);
        assertTrue("Callback and Appointment must be equal width", Math.abs(cb.getDouble("w") - appt.getDouble("w")) <= eps);
        assertTrue("Common action rows must use same half width", Math.abs(ni.getDouble("w") - cb.getDouble("w")) <= eps);
        assertTrue("NI height must equal Call Now", Math.abs(ni.getDouble("h") - call.getDouble("h")) <= eps);
        assertTrue("No Answer height must equal Call Now", Math.abs(no.getDouble("h") - call.getDouble("h")) <= eps);
        assertTrue("Callback height must equal Call Now", Math.abs(cb.getDouble("h") - call.getDouble("h")) <= eps);
        assertTrue("Appointment height must equal Call Now", Math.abs(appt.getDouble("h") - call.getDouble("h")) <= eps);
        assertTrue("NI/No Answer must share row", Math.abs(ni.getDouble("y") - no.getDouble("y")) <= eps);
        assertTrue("Callback/Appointment must share row", Math.abs(cb.getDouble("y") - appt.getDouble("y")) <= eps);
    }

    @Test
    public void callingDockMustDisappearOutsideHopper() throws Exception {
        seedTwoCallableLeads();
        for (String tab : new String[]{"leads", "schedule", "extras"}) {
            String display = evalString("setTab('" + tab + "',false);return getComputedStyle(document.querySelector('.actions')).display;");
            assertEquals("Calling dock must not cover the " + tab + " page", "none", display);
        }
        evalString("setTab('hopper',false);return 'ok';");
    }

    @Test
    public void activityBackControlReturnsToHopper() throws Exception {
        seedTwoCallableLeads();
        String result = evalString(
                "setTab('extras',false);v18GoHome();" +
                        "return (!document.getElementById('tab-hopper').classList.contains('hidden') && document.getElementById('tab-extras').classList.contains('hidden'))?'ok':'bad';"
        );
        assertEquals("ok", result);
    }

    @Test
    public void reportAndCsvExportsAreRoutedThroughNativeSaveBridge() throws Exception {
        assertTrue("AndroidBridge.saveText must be exposed", evalBoolean("window.AndroidBridge && typeof AndroidBridge.saveText==='function'"));
        assertTrue("Shared downloadText must route to AndroidBridge", evalBoolean("String(downloadText).includes('AndroidBridge.saveText')"));
        assertTrue("Activity CSV must use shared downloadText", evalBoolean("String(exportActivityCSV).includes('downloadText')"));
    }

    @Test
    public void noAnswerMovesLeadToTailAndAutoAdvances() throws Exception {
        seedTwoCallableLeads();
        assertEquals("A", evalString("return getCurrentLead().id;"));
        evalString("uiDisposition('No Answer');return 'saved';");
        Thread.sleep(900);
        String json = evalString("return JSON.stringify({current:getCurrentLead()&&getCurrentLead().id,order:state.leads.map(x=>x.id),disp:state.leads.find(x=>x.id==='A').disposition});");
        JSONObject o = new JSONObject(json);
        assertEquals("B", o.getString("current"));
        assertEquals("No Answer", o.getString("disp"));
        JSONArray order = o.getJSONArray("order");
        assertEquals("B", order.getString(0));
        assertEquals("A", order.getString(1));
    }

    @Test
    public void sameTimeDueCallbacksUseOriginalSchedulingOrder() throws Exception {
        seedTwoCallableLeads();
        String json = evalString(
                "state.leads.push({id:'C',firstName:'Current',lastName:'Caller',phone:'3175550103',disposition:'New',calls:[{t:new Date().toISOString()}],history:[]});" +
                        "state.currentIndex=2;state.cursorLeadId='C';state.holdLeadId='C';state.advance={ready:true,leadId:'C',disp:'No Answer',t:new Date().toISOString()};" +
                        "const due=new Date(Date.now()-60000).toISOString();" +
                        "state.schedule=[" +
                        "{id:'later-created',type:'callback',leadId:'B',when:due,createdAt:'2026-09-12T17:15:00.000Z',done:false}," +
                        "{id:'earlier-created',type:'callback',leadId:'A',when:due,createdAt:'2026-09-12T16:30:00.000Z',done:false}" +
                        "];saveAll();nextLead();" +
                        "return JSON.stringify({current:getCurrentLead()&&getCurrentLead().id,cursor:state.cursorLeadId});"
        );
        JSONObject o = new JSONObject(json);
        assertEquals("A", o.getString("current"));
        assertEquals("A", o.getString("cursor"));
    }

    @Test
    public void appointmentSpacingHonorsExactBoundary() throws Exception {
        seedTwoCallableLeads();
        String json = evalString(
                "const base=new Date(Date.now()+86400000);" +
                        "state.schedule=[{id:'appt0',type:'appointment',leadId:'B',when:base.toISOString(),createdAt:new Date().toISOString(),done:false,name:'Beta'}];" +
                        "const inside=new Date(base.getTime()+119*60000).toISOString();" +
                        "const boundary=new Date(base.getTime()+120*60000).toISOString();" +
                        "return JSON.stringify({inside:!!apptConflicts(inside),boundary:!!apptConflicts(boundary)});"
        );
        JSONObject o = new JSONObject(json);
        assertTrue(o.getBoolean("inside"));
        assertFalse(o.getBoolean("boundary"));
    }

    @Test
    public void persistedStateMustSurviveARealPageBoot() throws Exception {
        String before = evalString(
                "const s=JSON.parse(localStorage.getItem(STORE_KEY)||'{}');" +
                        "s.leads=[" +
                        "{id:'persist-current',firstName:'Current',lastName:'Resume',phone:'3175550188',disposition:'New',calls:[],history:[]}," +
                        "{id:'persist-sentinel',firstName:'Persistence',lastName:'Sentinel',phone:'3175550199',disposition:'Callback',calls:[],history:[]}" +
                        "];" +
                        "s.schedule=[{id:'persist-cb',type:'callback',leadId:'persist-sentinel',when:new Date(Date.now()+3600000).toISOString(),createdAt:new Date().toISOString(),done:false}];" +
                        "s.callLog=[{id:'persist-call',t:new Date().toISOString(),leadId:'persist-sentinel',phone:'3175550199'}];" +
                        "s.activityLog=[{id:'persist-act',t:new Date().toISOString(),leadId:'persist-sentinel',type:'TEST',detail:'must survive'}];" +
                        "s.currentIndex=0;s.cursorLeadId='persist-current';s.holdLeadId=null;s.overrideLeadId=null;s.activeTab='schedule';s.phoneQueue={};" +
                        "state=s;saveAll();return JSON.stringify(s);"
        );
        assertTrue(before.contains("persist-current"));
        assertTrue(before.contains("persist-sentinel"));
        String oldGeneration = evalString("return window.__LH_PAGE_GENERATION__;");
        assertNotNull("Page generation marker must exist before reload", oldGeneration);
        evalRaw("location.reload();return true;");
        waitForJs(
                "return document.readyState==='complete' && !!window.__LH_STORAGE_READY__ && window.__LH_PAGE_GENERATION__!==" +
                        JSONObject.quote(oldGeneration) + ";",
                15_000
        );
        String after = evalString(
                "return JSON.stringify({" +
                        "currentLead:state.leads.some(x=>x.id==='persist-current')," +
                        "sentinelLead:state.leads.some(x=>x.id==='persist-sentinel')," +
                        "schedule:state.schedule.some(x=>x.id==='persist-cb'&&x.leadId==='persist-sentinel')," +
                        "call:state.callLog.some(x=>x.leadId==='persist-sentinel')," +
                        "activity:state.activityLog.some(x=>x.id==='persist-act')," +
                        "cursor:state.cursorLeadId," +
                        "tab:state.activeTab" +
                        "});"
        );
        JSONObject o = new JSONObject(after);
        assertTrue("Persisted current lead was destroyed during boot", o.getBoolean("currentLead"));
        assertTrue("Persisted callback owner was destroyed during boot", o.getBoolean("sentinelLead"));
        assertTrue("Persisted callback was destroyed during boot", o.getBoolean("schedule"));
        assertTrue("Persisted call history was destroyed during boot", o.getBoolean("call"));
        assertTrue("Persisted activity history was destroyed during boot", o.getBoolean("activity"));
        assertEquals("A still-eligible saved cursor must resume after boot", "persist-current", o.getString("cursor"));
        assertEquals("schedule", o.getString("tab"));
    }

    @Test
    public void captureVisualSweep() throws Exception {
        String prefix = InstrumentationRegistry.getArguments().getString("shotPrefix", "default");
        seedTwoCallableLeads();
        takeScreenshot(prefix + "_01_hopper");

        evalString("v13OpenMenu();return 'ok';");
        Thread.sleep(150);
        takeScreenshot(prefix + "_02_menu");
        evalString("v13CloseMenu();return 'ok';");

        evalString("setTab('leads',false);renderLeadsTable();return 'ok';");
        Thread.sleep(120);
        takeScreenshot(prefix + "_03_all_leads");

        evalString("setTab('schedule',false);renderSchedule();return 'ok';");
        Thread.sleep(120);
        takeScreenshot(prefix + "_04_schedule");

        evalString("setTab('extras',false);renderActivityLog();return 'ok';");
        Thread.sleep(120);
        takeScreenshot(prefix + "_05_reports");

        evalString("setTab('hopper',false);openCallbackModalFromLead(getCurrentLead());return 'ok';");
        Thread.sleep(120);
        takeScreenshot(prefix + "_06_callback_modal");
        evalString("closeCallback();openApptModalFromLead(getCurrentLead());return 'ok';");
        Thread.sleep(120);
        takeScreenshot(prefix + "_07_appointment_modal");
        evalString("closeAppt();uiNoEnglish();return 'ok';");
        Thread.sleep(120);
        takeScreenshot(prefix + "_08_no_english_modal");
        evalString("closeNoEnglish();return 'ok';");

        evalString(
                "const l=getCurrentLead();l.firstName='Alexandria-Cassandra';l.lastName='Extremely-Long-Hyphenated-Household-Name';" +
                        "l.email='a.very.long.email.address.with.tags+sales@example-super-long-domain.test';" +
                        "l.address='12345 A Very Long Residential Street Name Apartment 999';l.city='Indianapolis';l.state='IN';l.zip='46201';renderAll();return 'ok';"
        );
        Thread.sleep(120);
        takeScreenshot(prefix + "_09_long_content");

        writeTextArtifact(prefix + "_dom_metrics.json", evalString(
                "const a=document.documentElement;const d=document.querySelector('.actions').getBoundingClientRect();" +
                        "return JSON.stringify({innerWidth:innerWidth,innerHeight:innerHeight,scrollWidth:a.scrollWidth,scrollHeight:a.scrollHeight,dock:{x:d.x,y:d.y,w:d.width,h:d.height},tab:state.activeTab});"
        ));
    }

    private void takeScreenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("UiAutomation screenshot failed", bitmap);
        File dir = new File(instrumentation.getTargetContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "torture");
        assertTrue(dir.exists() || dir.mkdirs());
        File out = new File(dir, name + ".png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos));
        } finally {
            bitmap.recycle();
        }
    }

    private void writeTextArtifact(String name, String text) throws Exception {
        File dir = new File(instrumentation.getTargetContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "torture");
        assertTrue(dir.exists() || dir.mkdirs());
        try (FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
            fos.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        }
    }
}
