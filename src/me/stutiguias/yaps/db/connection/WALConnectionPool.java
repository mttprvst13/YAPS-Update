/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package me.stutiguias.yaps.db.connection;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import me.stutiguias.yaps.init.Yaps;

public class WALConnectionPool {
    
    private static WALConnectionPool instance;
    
    public static WALConnectionPool getInstance() {
            return instance;
    }
    
    private boolean ready = false;
    private static int poolsize;
    private static List<WALConnection> connections;
    private static final long timeToLive = 300000;
    private final ConnectionReaper reaper;
    private final String url;
    private final String username;
    private final String password;
    
    public WALConnectionPool(String driverName, String url, String username, String password) throws InstantiationException, IllegalAccessException, ClassNotFoundException, SQLException {
        Driver driver = (Driver) Class.forName(driverName).newInstance();
        WALDriver jDriver = new WALDriver(driver);
        DriverManager.registerDriver(jDriver);
        poolsize = Yaps.config.ConnectionPoolSize;
        ready = true;
        this.url = url;
        this.username = username;
        this.password = password;
        connections = new ArrayList<>(poolsize);
        reaper = new ConnectionReaper();
        StartReaper();
        instance = this;
    }
    
    public final void StartReaper() {
        reaper.start();
    }
    
    public boolean isReady(){
		return ready;
	}
  

    public synchronized WALConnection getConnection() throws SQLException {
        if (!ready) {
            return null;
        }
        WALConnection conn;
        for (int i = 0; i < connections.size(); i++) {
            conn = connections.get(i);
            if (!conn.lease()) continue;
            if (conn.isValid()) return conn;
            connections.remove(conn);
            conn.terminate();

        }
        conn = new WALConnection(DriverManager.getConnection(url, username, password));
        conn.lease();
        if (!conn.isValid()) {
            conn.terminate();
            throw new SQLException("Could not create new connection");
        }
        connections.add(conn);
        return conn;
    }
    
    public static synchronized void removeConn(Connection conn) {
		connections.remove((WALConnection) conn);
    }
    
    public synchronized void closeConnection() {
            ready = false;
            for (WALConnection conn : connections) {
                    conn.terminate();
            }
            connections.clear();
    }
    
    private synchronized void reapConnections() {
        if (!ready) {
            return;
        }

        final long stale = System.currentTimeMillis() - timeToLive;
        int i = 1;
        for (final WALConnection conn : connections) {
            if (conn.inUse() && stale > conn.getLastUse() && !conn.isValid()) {
                connections.remove(conn);
            }

            if (i > poolsize) {
                connections.remove(conn);
                conn.terminate();
            }
            
            i++;
        }
    }

    private class ConnectionReaper extends Thread {

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(300000);
                } catch (final InterruptedException e) {
                }
                reapConnections();
            }
        }
    }
}