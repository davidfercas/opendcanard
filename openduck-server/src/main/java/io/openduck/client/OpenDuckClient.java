package io.openduck.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class OpenDuckClient implements AutoCloseable {

    private final BufferAllocator allocator;
    private final FlightClient client;
    private final String token;

    public OpenDuckClient(String host, int port, String token) {
        this.allocator = new RootAllocator(Long.MAX_VALUE);
        Location location = Location.forGrpcInsecure(host, port);
        this.client = FlightClient.builder(allocator, location).build();
        this.token = token;
        
        
    }

    public void query(String sql) throws Exception {
        FlightDescriptor descriptor =
                FlightDescriptor.command(sql.getBytes(StandardCharsets.UTF_8));


        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String rawPassword = "admin";
        String hashedPassword = encoder.encode(rawPassword);
        
        CallOption basicAuth = createBasicAuthOption("admin", rawPassword);
    //    CallOption tokenAuth = createBearerTokenOption("openduck-secret-token");
        

     // 3. Example: Call listFlights using Token
   //     System.out.println("Attempting call with Token...");
   //     client.listFlights(org.apache.arrow.flight.Criteria.ALL, tokenAuth).forEach(info -> System.out.println("Found flight: " + info.getDescriptor()));

        // 4. Example: Call listFlights using Username/Password
        System.out.println("Attempting call with Basic Auth...");
//        client.listFlights(org.apache.arrow.flight.Criteria.ALL, basicAuth).forEach(info -> System.out.println("Found flight: " + info.getDescriptor()));
        
        FlightInfo info = client.getInfo(descriptor, basicAuth);

        for (FlightEndpoint endpoint : info.getEndpoints()) {
            try (FlightStream stream = client.getStream(endpoint.getTicket(), basicAuth)) {
                VectorSchemaRoot root = stream.getRoot();

                while (stream.next()) {
                    System.out.println("Batch received:");
                    System.out.println(root.contentToTSVString());
                }
            }
        }
    }

    @Override
    public void close() {
        try {
			client.close();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        allocator.close();
    }
    
    
    
    /** Creates a CallOption for Basic Authentication (User/Pass) */
    private static CallOption createBasicAuthOption(String user, String pass) {
        String combined = user + ":" + pass;
        String encoded = Base64.getEncoder().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
        
        CallHeaders headers = new FlightCallHeaders();
        headers.insert("Authorization", "Basic " + encoded);
        return new HeaderCallOption(headers);
    }

    /** Creates a CallOption for Bearer Token Authentication */
    private static CallOption createBearerTokenOption(String token) {
        CallHeaders headers = new FlightCallHeaders();
        headers.insert("Authorization", "Bearer " + token);
        return new HeaderCallOption(headers);
    }
    

    public static void main(String[] args) throws Exception {
        try (OpenDuckClient client =
                     new OpenDuckClient("localhost", 8815, "mysecret123")) {

        //	client.query("SELECT * FROM cities limit 5");
        //	client.query("SELECT count(*) FROM cities");
          //  client.query("SELECT 42 AS answer");
            //client.query("SELECT range AS n FROM range(5)");
        	
        	
        //	client.query("SELECT table_name FROM information_schema.tables WHERE table_schema = 'main'");
        //	client.query("SELECT * FROM read_csv_auto('D:\\Duckdb\\countries.csv') order by id limit 5");
        	client.query("SELECT countries.name as country, cities.name as city  FROM read_csv_auto('D:\\Duckdb\\countries.csv') as countries"
        			+ " inner join cities on countries.id = cities.country_id "
        			+ "order by countries.name, cities.name");
        //	client.query("SELECT schema_name AS TABLE_SCHEM, NULL AS TABLE_CATALOG FROM information_schema.schemata");
        	System.out.println("End");
        			
        }
    }
}
