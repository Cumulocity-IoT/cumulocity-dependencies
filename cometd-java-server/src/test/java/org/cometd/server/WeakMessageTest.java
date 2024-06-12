package org.cometd.server;

import org.apache.commons.io.IOUtils;
import org.cometd.bayeux.Message;
import org.cometd.common.JSONContext;
import org.eclipse.jetty.util.ajax.JSON;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.ParseException;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.skyscreamer.jsonassert.JSONCompareMode.NON_EXTENSIBLE;

public class WeakMessageTest {

    private final JSONContext.Server jsonContext = new JettyJSONContextServer();
    private final JSON generator = new JSON();

    private final static String DATA = "JsonData";

    @Test
    public void shouldGenerateSameJSON() throws ParseException, JSONException {
        // given
        String jsonData = "{\"realtimeAction\":\"UPDATE\",\"data\":{\"additionParents\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/additionParents\",\"references\":[]},\"owner\":\"admin\",\"childDevices\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childDevices\",\"references\":[]},\"childAssets\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childAssets\",\"references\":[{\"managedObject\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3200\",\"id\":\"3200\"},\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childAssets/3200\"}]},\"creationTime\":\"2020-08-28T09:20:30.186Z\",\"lastUpdated\":\"2020-08-28T09:20:30.186Z\",\"childAdditions\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childAdditions\",\"references\":[]},\"name\":\"testGroup1\",\"assetParents\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/assetParents\",\"references\":[]},\"deviceParents\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/deviceParents\",\"references\":[]},\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201\",\"id\":\"3201\",\"c8y_IsDeviceGroup\":{}}}";
        Map data = WeakMessage.parseJsonToMap(jsonData, jsonContext);
        ServerMessageImpl serverMessage = new ServerMessageImpl();
        serverMessage.setData(data);
        serverMessage.setChannel("/some/setChannel/*");
        serverMessage.setId("123");
        serverMessage.setClientId("321");

        // when
        WeakMessage weakMessage = new WeakMessage(serverMessage, 50000, jsonContext, true);
        weakMessage.freeze();

        // then
        JSONAssert.assertEquals(serverMessage.getJSON(), weakMessage.getJSON(), false);
    }

    @Test
    public void shouldGenerateSameJSONWhenDataNull() throws JSONException {
        // given
        ServerMessageImpl serverMessage = new ServerMessageImpl();
        serverMessage.setData(null);
        serverMessage.setChannel("/some/setChannel/*");
        serverMessage.setId("123");
        serverMessage.setClientId("321");

        // when
        WeakMessage weakMessage = new WeakMessage(serverMessage, 50000, jsonContext, true);
        weakMessage.freeze();

        // then
        assertThat(weakMessage.getJSON()).doesNotContain("data");
        assertThat(weakMessage.getJSON()).doesNotContain("null");
        JSONAssert.assertEquals(serverMessage.getJSON(), weakMessage.getJSON(), NON_EXTENSIBLE);
    }

    @Test
    public void shouldLeaveNullDataFieldIfFeatureFlagConfigured() {
        // given
        ServerMessageImpl serverMessage = new ServerMessageImpl();
        serverMessage.setData(null);
        serverMessage.setChannel("/some/setChannel/*");
        serverMessage.setId("123");
        serverMessage.setClientId("321");

        // when
        WeakMessage weakMessage = new WeakMessage(serverMessage, 50000, jsonContext, false);
        weakMessage.freeze();

        // then
        assertThat(weakMessage.getJSON()).contains("\"data\":null");
    }

    @Test
    public void shouldGenerateSameJSONDataWhenGCWeakReference() throws ParseException, JSONException {
        // given
        String jsonData = new String("{\"realtimeAction\":\"UPDATE\",\"data\":{\"additionParents\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/additionParents\",\"references\":[]},\"owner\":\"admin\",\"childDevices\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childDevices\",\"references\":[]},\"childAssets\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childAssets\",\"references\":[{\"managedObject\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3200\",\"id\":\"3200\"},\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childAssets/3200\"}]},\"creationTime\":\"2020-08-28T09:20:30.186Z\",\"lastUpdated\":\"2020-08-28T09:20:30.186Z\",\"childAdditions\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childAdditions\",\"references\":[]},\"name\":\"testGroup1\",\"assetParents\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/assetParents\",\"references\":[]},\"deviceParents\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/deviceParents\",\"references\":[]},\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201\",\"id\":\"3201\",\"c8y_IsDeviceGroup\":{}}}");
        Map weakData = WeakMessage.parseJsonToMap(jsonData, jsonContext);

        ServerMessageImpl serverMessage = new ServerMessageImpl();
        serverMessage.setData(weakData);
        serverMessage.setChannel("/some/setChannel/*");
        serverMessage.setId("123");
        serverMessage.setClientId("321");

        WeakMessage weakMessage = new WeakMessage(serverMessage, 50000, jsonContext, true);
        weakMessage.freeze();
        String copyOfServerMessageJsonData = generator.toJSON(serverMessage.getData());

        // when
        jsonData = null;
        weakData = null;
        serverMessage = null;
        System.gc();

        // then
        JSONAssert.assertEquals(copyOfServerMessageJsonData, generator.toJSON(weakMessage.getData()), false);
    }

    @Test
    public void shouldGetDataFromJson() {
        // given
        WeakMessage weakMessage = new WeakMessage(50000, jsonContext, true);
        String weakData = new String("WeakReferenceData");
        weakMessage.setData(weakData);
        weakMessage.freeze();
        String json = "{\"data\":\"JsonData\"}";

        // when
        weakMessage.getJSON(); //lazily generate json, set WeakReference on frozen
        replaceJson(weakMessage, json);
        weakData = null;
        System.gc(); // clear data in WeakReference

        // then
        assertThat(weakMessage.get("data")).isEqualTo("JsonData");
    }

    @Test
    public void shouldGetDataFromWeakReference_NotFromJsonWhenNoGC() {
        // given
        WeakMessage weakMessage = new WeakMessage(50000, jsonContext, true);
        String weakData = new String("WeakReferenceData");
        weakMessage.setData(weakData);

        // when
        weakData = null;
        String json = "{\"data\":\"JsonData\"}";
        weakMessage.freeze();
        String generatedJson = weakMessage.getJSON();
        replaceJson(weakMessage, json);

        // then
        assertThat(generatedJson).isNotEqualTo(json);
        assertThat(weakMessage.get("data")).isEqualTo("WeakReferenceData");
    }

    @Test
    public void shouldGCWeakReferenceFromWeakMessageAndGetDataFromJson() throws ParseException {
        // given
        WeakMessage weakMessage = new WeakMessage(50000, jsonContext, true);
        String weakData = new String("WeakReferenceData");
        weakMessage.setData(weakData);

        String json = "{\"data\":\"JsonData\"}";
        weakMessage.freeze();
        // when
        weakMessage.getJSON(); //lazily generate Json, set weak reference
        replaceJson(weakMessage, json);

        weakData = null;
        System.gc(); // clear data in WeakReference
        String data = (String) weakMessage.get("data");

        // then
        assertThat(data).isEqualTo("JsonData");
    }

    @Test
    public void shouldZipJsonData_WhenZipMessageThresholdReached() throws IOException {
        // given
        WeakMessage weakMessage = new WeakMessage(0, jsonContext, true);
        weakMessage.setData(DATA);

        // when
        weakMessage.freeze();

        // then
        String unzipData = IOUtils.toString(new GZIPInputStream(new ByteArrayInputStream(weakMessage.getRawData())));
        assertThat(unzipData).contains("\"data\":\"JsonData\"");
    }

    @Test
    public void shouldNotZipJsonData_WhenZipMessageNotReachedThreshold() throws JSONException {
        // given
        WeakMessage weakMessage = new WeakMessage(givenMessage(), 50000, jsonContext, true);

        // when
        weakMessage.freeze();

        // then
        JSONAssert.assertEquals("{\"clientId\":\"clientId123\",\"data\":\"JsonData\",\"channel\":\"channel123\",\"id\":\"id123\"}",
                new String(weakMessage.getRawData()), false);
    }

    @Test
    public void shouldCreateShallowMessageCopy() throws ParseException {
        // given
        String jsonData = "{\"realtimeAction\":\"UPDATE\",\"data\":{\"additionParents\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/additionParents\",\"references\":[]},\"owner\":\"admin\",\"childDevices\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childDevices\",\"references\":[]},\"childAssets\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childAssets\",\"references\":[{\"managedObject\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3200\",\"id\":\"3200\"},\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childAssets/3200\"}]},\"creationTime\":\"2020-08-28T09:20:30.186Z\",\"lastUpdated\":\"2020-08-28T09:20:30.186Z\",\"childAdditions\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/childAdditions\",\"references\":[]},\"name\":\"testGroup1\",\"assetParents\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/assetParents\",\"references\":[]},\"deviceParents\":{\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201/deviceParents\",\"references\":[]},\"self\":\"http://cumulocity.default.svc.cluster.local/inventory/managedObjects/3201\",\"id\":\"3201\",\"c8y_IsDeviceGroup\":{}}}";
        Map data = WeakMessage.parseJsonToMap(jsonData, jsonContext);
        WeakMessage weakMessage = new WeakMessage(50000, jsonContext, true);
        weakMessage.setData(data);
        weakMessage.setChannel("/some/setChannel/*");
        weakMessage.setId("123");
        weakMessage.setClientId("321");

        // when
        WeakMessage copyOfWeakMessage = weakMessage.copy();

        // then
        assertThat(copyOfWeakMessage.get("data")).isEqualTo(weakMessage.get("data"));
        assertThat(copyOfWeakMessage.get("channel")).isEqualTo(weakMessage.get("channel"));
        assertThat(copyOfWeakMessage.get("id")).isEqualTo(weakMessage.get("id"));
    }

    private Message givenMessage() {
        ServerMessageImpl serverMessage = new ServerMessageImpl();
        serverMessage.setClientId("clientId123");
        serverMessage.setChannel("channel123");
        serverMessage.setId("id123");
        serverMessage.setData(DATA);
        return serverMessage;
    }

    private void replaceJson(WeakMessage message, String json) {
        ReflectionTestUtils.setField(message, "_json", json);
    }
}
