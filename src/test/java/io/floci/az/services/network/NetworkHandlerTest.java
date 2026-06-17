package io.floci.az.services.network;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@DisplayName("NetworkHandler - ARM Virtual Network compatibility")
class NetworkHandlerTest {

    private static final String SUB = "test-sub-net";
    private static final String RG = "test-rg-net";
    private static final String API = "?api-version=2024-05-01";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.Network";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void virtualNetworkLifecycleAndSubnetListing() {
        String vnetBody = """
                {
                  "location": "eastus",
                  "tags": {"env": "test"},
                  "properties": {
                    "addressSpace": {
                      "addressPrefixes": ["10.10.0.0/16"]
                    }
                  }
                }
                """;

        given().contentType("application/json").body(vnetBody)
                .when().put(BASE + "/virtualNetworks/vnet1" + API)
                .then().statusCode(200)
                .body("name", equalTo("vnet1"))
                .body("type", equalTo("Microsoft.Network/virtualNetworks"))
                .body("location", equalTo("eastus"))
                .body("tags.env", equalTo("test"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.addressSpace.addressPrefixes", hasItem("10.10.0.0/16"));

        given().when().get(BASE + "/virtualNetworks" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("vnet1"));

        String subnetBody = """
                {
                  "properties": {
                    "addressPrefix": "10.10.1.0/24"
                  }
                }
                """;

        given().contentType("application/json").body(subnetBody)
                .when().put(BASE + "/virtualNetworks/vnet1/subnets/default" + API)
                .then().statusCode(200)
                .body("name", equalTo("default"))
                .body("type", equalTo("Microsoft.Network/virtualNetworks/subnets"))
                .body("properties.addressPrefix", equalTo("10.10.1.0/24"))
                .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(BASE + "/virtualNetworks/vnet1/subnets" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("default"));

        given().when().delete(BASE + "/virtualNetworks/vnet1" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/virtualNetworks/vnet1" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void networkInterfaceSynthesizesPrivateIpForVmCompatibility() {
        String nicBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "ipConfigurations": [
                      {
                        "name": "ipconfig1",
                        "properties": {
                          "subnet": {
                            "id": "/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Network/virtualNetworks/vnet1/subnets/default"
                          }
                        }
                      }
                    ]
                  }
                }
                """;

        given().contentType("application/json").body(nicBody)
                .when().put(BASE + "/networkInterfaces/nic1" + API)
                .then().statusCode(200)
                .body("name", equalTo("nic1"))
                .body("type", equalTo("Microsoft.Network/networkInterfaces"))
                .body("properties.ipConfigurations[0].properties.privateIPAddress", equalTo("10.0.0.4"))
                .body("properties.ipConfigurations[0].properties.privateIPAllocationMethod", equalTo("Dynamic"))
                .body("properties.ipConfigurations[0].properties.primary", equalTo(true))
                .body("properties.ipConfigurations[0].properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    void dnsZoneAndRecordSetsLifecycleAndConcurrency() {
        String zoneBody = """
                {
                  "location": "global",
                  "tags": {"project": "floci"},
                  "properties": {
                    "zoneType": "Public"
                  }
                }
                """;

        // 1. Create DNS Zone
        given().contentType("application/json").body(zoneBody)
                .when().put(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(201)
                .body("name", equalTo("example.com"))
                .body("type", equalTo("Microsoft.Network/dnsZones"))
                .body("location", equalTo("global"))
                .body("tags.project", equalTo("floci"))
                .body("properties.zoneType", equalTo("Public"))
                .body("properties.numberOfRecordSets", equalTo(2))
                .body("properties.nameServers", hasSize(4));

        // 2. Get default SOA and NS records
        given().when().get(BASE + "/dnsZones/example.com/SOA/@" + API)
                .then().statusCode(200)
                .body("name", equalTo("@"))
                .body("type", equalTo("Microsoft.Network/dnsZones/SOA"))
                .body("properties.fqdn", equalTo("example.com."))
                .body("properties.SOARecord.host", equalTo("ns1-01.azure-dns.com."));

        given().when().get(BASE + "/dnsZones/example.com/NS/@" + API)
                .then().statusCode(200)
                .body("name", equalTo("@"))
                .body("type", equalTo("Microsoft.Network/dnsZones/NS"))
                .body("properties.fqdn", equalTo("example.com."))
                .body("properties.NSRecords[0].nsdname", equalTo("ns1-01.azure-dns.com."));

        // 3. Create A Record Set
        String aRecordBody = """
                {
                  "properties": {
                    "TTL": 600,
                    "ARecords": [
                      {"ipv4Address": "192.168.1.1"}
                    ]
                  }
                }
                """;

        String etag = given().contentType("application/json").body(aRecordBody)
                .when().put(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(201)
                .body("name", equalTo("www"))
                .body("type", equalTo("Microsoft.Network/dnsZones/A"))
                .body("properties.fqdn", equalTo("www.example.com."))
                .body("properties.TTL", equalTo(600))
                .body("properties.ARecords[0].ipv4Address", equalTo("192.168.1.1"))
                .extract().path("etag");

        // 4. Verify parent zone record count incremented to 3
        given().when().get(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(200)
                .body("properties.numberOfRecordSets", equalTo(3));

        // 5. Test concurrency checks
        // PUT with wrong If-Match should fail
        given().contentType("application/json").body(aRecordBody)
                .header("If-Match", "\"wrong-etag\"")
                .when().put(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(412);

        // PUT with If-None-Match: * should fail
        given().contentType("application/json").body(aRecordBody)
                .header("If-None-Match", "*")
                .when().put(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(412);

        // PUT with correct If-Match should succeed
        String newEtag = given().contentType("application/json")
                .body("""
                      {
                        "properties": {
                          "TTL": 300,
                          "aRecords": [{"ipv4Address": "10.0.0.1"}]
                        }
                      }
                      """)
                .header("If-Match", etag)
                .when().put(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(200)
                .body("properties.TTL", equalTo(300))
                .body("properties.ARecords[0].ipv4Address", equalTo("10.0.0.1"))
                .extract().path("etag");

        // 6. Create CNAME and TXT records
        given().contentType("application/json")
                .body("""
                      {
                        "properties": {
                          "TTL": 3600,
                          "cnameRecord": {"cname": "www.example.com"}
                        }
                      }
                      """)
                .when().put(BASE + "/dnsZones/example.com/CNAME/alias" + API)
                .then().statusCode(201);

        given().contentType("application/json")
                .body("""
                      {
                        "properties": {
                          "TTL": 3600,
                          "txtRecords": [{"value": ["v=spf1 include:spf.protection.outlook.com -all"]}]
                        }
                      }
                      """)
                .when().put(BASE + "/dnsZones/example.com/TXT/@" + API)
                .then().statusCode(201);

        // 7. List all record sets
        given().when().get(BASE + "/dnsZones/example.com/recordsets" + API)
                .then().statusCode(200)
                .body("value", hasSize(5)); // SOA@, NS@, A/www, CNAME/alias, TXT@

        // 8. List record sets by type
        given().when().get(BASE + "/dnsZones/example.com/A" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("www"));

        // 9. Try to delete default root NS record (should fail)
        given().when().delete(BASE + "/dnsZones/example.com/NS/@" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadRequest"));

        // 10. Delete custom A record
        given().when().delete(BASE + "/dnsZones/example.com/A/www" + API)
                .then().statusCode(200);

        // Parent zone count decremented back to 4 (NS@, SOA@, CNAME/alias, TXT@)
        given().when().get(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(200)
                .body("properties.numberOfRecordSets", equalTo(4));

        // 11. Delete DNS Zone
        given().when().delete(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(200);

        // Zone not found
        given().when().get(BASE + "/dnsZones/example.com" + API)
                .then().statusCode(404);

        // Records also deleted
        given().when().get(BASE + "/dnsZones/example.com/CNAME/alias" + API)
                .then().statusCode(404);
    }
}
