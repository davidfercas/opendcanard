package io.openduck.server;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;

public class Main {
    public static void main(String[] args) throws Exception {

    	// -Djava.io.tmpdir=/path/to/a/bigger/disk/folder
    	// Standard practice: Use try-with-resources to prevent memory leaks
    	try (BufferAllocator rootAllocator = new RootAllocator(128 * 128 * 128L)) {
    	    // You now have a BufferAllocator instance!
    	    // By default, it has no limit, but you can set one:
    	    // new RootAllocator(1024 * 1024 * 1024L); // 1GB Limit
    	
		        OpenDuckFlightServer server = new OpenDuckFlightServer(
		                "0.0.0.0", 8815, "D:\\GitProjects\\openduck\\openduck.duckdb", rootAllocator
		        );
		
		        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
		            try { server.close(); } catch (Exception ignored) {}
		        }));
		
		        server.start();
		
		        System.out.println("Press Ctrl+C to stop.");
		        Thread.currentThread().join();
        
    	}
    }
}
