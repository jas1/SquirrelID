/*
 * SquirrelID, a UUID library for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) SquirrelID team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.squirrelid.cache;

import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An implementation of a UUID cache using a SQLite database.
 *
 * <p>The implementation performs all requests in a single thread, so
 * calls may block for a short period of time.</p>
 */
public class SQLiteCache extends AbstractUUIDCache {

    private final Connection connection;
    private final PreparedStatement updateStatement;

    /**
     * Create a new instance.
     *
     * @param file the path to a SQLite file to use
     * @throws CacheException thrown on a cache error
     */
    public SQLiteCache(File file) throws CacheException {
        checkNotNull(file);

        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        } catch (ClassNotFoundException e) {
            throw new CacheException("SQLite JDBC support is not installed");
        } catch (SQLException e) {
            throw new CacheException("Failed to connect to cache file", e);
        }

        try {
            createTable();
        } catch (SQLException e) {
            throw new CacheException("Failed to create tables", e);
        }

        try {
            updateStatement = connection.prepareStatement("INSERT OR REPLACE INTO uuid_cache (name, uuid) VALUES (?, ?)");
        } catch (SQLException e) {
            throw new CacheException("Failed to prepare statements", e);
        }
    }

    /**
     * Get the connection.
     *
     * @return a connection
     * @throws SQLException thrown on error
     */
    protected Connection getConnection() throws SQLException {
        return connection;
    }

    /**
     * Create the necessary tables and indices if they do not exist yet.
     *
     * @throws SQLException thrown on error
     */
    private void createTable() throws SQLException {
        Connection conn = getConnection();
        Statement stmt = conn.createStatement();
        stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS uuid_cache (\n" +
                "  uuid CHAR(36) PRIMARY KEY NOT NULL,\n" +
                "  name CHAR(32) NOT NULL)");

        try {
            stmt.executeUpdate("CREATE INDEX name_index ON uuid_cache (name)");
        } catch (SQLException ignored) {
            // Index may already exist
        }
        stmt.close();
    }

    @Override
    public void putAll(Map<UUID, String> entries) throws CacheException {
        try {
            executePut(entries);
        } catch (SQLException e) {
            throw new CacheException("Failed to execute queries", e);
        }
    }

    @Override
    public ImmutableMap<UUID, String> getAllPresent(Iterable<UUID> uuids) throws CacheException {
        try {
            return executeGet(uuids);
        } catch (SQLException e) {
            throw new CacheException("Failed to execute queries", e);
        }
    }

    protected synchronized void executePut(Map<UUID, String> entries) throws SQLException {
        for (Map.Entry<UUID, String> entry : entries.entrySet()) {
            updateStatement.setString(1, entry.getValue());
            updateStatement.setString(2, entry.getKey().toString());
            updateStatement.executeUpdate();
        }
    }

    protected ImmutableMap<UUID, String> executeGet(Iterable<UUID> uuids) throws SQLException {
        StringBuilder builder = new StringBuilder();
        builder.append("SELECT name, uuid FROM uuid_cache WHERE uuid IN (");

        boolean first = true;
        for (UUID uuid : uuids) {
            checkNotNull(uuid, "Unexpected null UUID");
            
            if (!first) {
                builder.append(", ");
            }
            builder.append("'").append(uuid).append("'");
            first = false;
        }

        // It was an empty collection
        if (first) {
            return ImmutableMap.of();
        }

        builder.append(")");

        synchronized (this) {
            Connection conn = getConnection();
            Statement stmt = conn.createStatement();
            try {
                ResultSet rs = stmt.executeQuery(builder.toString());
                Map<UUID, String> map = new HashMap<UUID, String>();

                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    map.put(uuid, rs.getString("name"));
                }

                return ImmutableMap.copyOf(map);
            } finally {
                stmt.close();
            }
        }
    }

}
