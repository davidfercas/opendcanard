package io.openduck.client;

import org.apache.arrow.flight.*;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;

import java.nio.charset.StandardCharsets;

import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.FlightCallHeaders;


public class OpenDuckFlightClient implements AutoCloseable {

    private final BufferAllocator allocator;
    private final FlightClient client;

    public OpenDuckFlightClient(String host, int port) {
        this.allocator = new RootAllocator(Long.MAX_VALUE);
        Location location = Location.forGrpcInsecure(host, port);
        this.client = FlightClient.builder(allocator, location).build();
    }

    public void query(String sql) throws Exception {
        FlightDescriptor descriptor =
                FlightDescriptor.command(sql.getBytes(StandardCharsets.UTF_8));



        // 1. Create a new headers container
        FlightCallHeaders headers = new FlightCallHeaders();

        // 2. Add your custom headers (Key-Value pairs)
       // headers.insert("Authorization", "Bearer your-secret-token");
        headers.insert("x-tenant-id", "openduck-01");

        // 3. Instantiate the CallOption using the headers
        HeaderCallOption headerOption = new HeaderCallOption(headers);
        
        FlightInfo info = client.getInfo(descriptor, headerOption);

        for (FlightEndpoint endpoint : info.getEndpoints()) {
            try (FlightStream stream = client.getStream(endpoint.getTicket())) {
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

    public static void main(String[] args) throws Exception {
        try (OpenDuckFlightClient client =
                     new OpenDuckFlightClient("localhost", 8815)) {

        	//client.query("SELECT * FROM cities");
          //  client.query("SELECT 42 AS answer");
            client.query("SELECT range AS n FROM range(5)");
        }
    }
}
