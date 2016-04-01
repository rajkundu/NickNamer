/*
 * Copyright 2015-2016 inventivetalent. All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without modification, are
 *  permitted provided that the following conditions are met:
 *
 *     1. Redistributions of source code must retain the above copyright notice, this list of
 *        conditions and the following disclaimer.
 *
 *     2. Redistributions in binary form must reproduce the above copyright notice, this list
 *        of conditions and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 *  WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 *  FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 *  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *  CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 *  SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 *  ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 *  ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *  The views and conclusions contained in the software and documentation are those of the
 *  authors and contributors and should not be interpreted as representing official policies,
 *  either expressed or implied, of anybody else.
 */

package org.inventivetalent.nicknamer;

import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.inventivetalent.apihelper.APIManager;
import org.inventivetalent.data.api.bukkit.ebean.EbeanDataProvider;
import org.inventivetalent.data.api.bukkit.ebean.KeyValueBean;
import org.inventivetalent.data.api.redis.RedisDataProvider;
import org.inventivetalent.data.sql.SQLDataProvider;
import org.inventivetalent.nicknamer.api.NickManagerImpl;
import org.inventivetalent.nicknamer.api.NickNamerAPI;
import org.inventivetalent.nicknamer.api.SkinLoader;
import org.inventivetalent.nicknamer.api.event.replace.ChatInReplacementEvent;
import org.inventivetalent.nicknamer.api.event.replace.ChatOutReplacementEvent;
import org.inventivetalent.nicknamer.api.event.replace.ChatReplacementEvent;
import org.inventivetalent.nicknamer.command.NickCommands;
import org.inventivetalent.nicknamer.command.SkinCommands;
import org.inventivetalent.nicknamer.database.NickEntry;
import org.inventivetalent.nicknamer.database.SkinDataEntry;
import org.inventivetalent.nicknamer.database.SkinEntry;
import org.inventivetalent.packetlistener.PacketListenerAPI;
import org.inventivetalent.pluginannotations.PluginAnnotations;
import org.inventivetalent.pluginannotations.config.ConfigValue;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

import javax.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class NickNamerPlugin extends JavaPlugin implements Listener {

	public static NickNamerPlugin instance;

	public NickCommands nickCommands;
	public SkinCommands skinCommands;

	//	@ConfigValue(path = "replace.tab") boolean replaceTab;
	@ConfigValue(path = "replace.chat.player")     boolean replaceChatPlayer;
	@ConfigValue(path = "replace.chat.out")        boolean replaceChatOut;
	@ConfigValue(path = "replace.chat.in.general") boolean replaceChatInGeneral;
	@ConfigValue(path = "replace.chat.in.command") boolean replaceChatInCommand;

	@ConfigValue(path = "storage.type") String storageType = "local";

	@ConfigValue(path = "storage.sql.host") String sqlHost;
	@ConfigValue(path = "storage.sql.port") int    sqlPort;
	@ConfigValue(path = "storage.sql.user") String sqlUser;
	@ConfigValue(path = "storage.sql.pass") String sqlPass;

	@ConfigValue(path = "storage.redis.host")            String redisHost;
	@ConfigValue(path = "storage.redis.port")            int    redisPort;
	@ConfigValue(path = "storage.redis.pass")            String redisPass;
	@ConfigValue(path = "storage.redis.max-connections") int    redisMaxConnections;

	@Override
	public void onLoad() {
		APIManager.require(PacketListenerAPI.class, this);
		APIManager.registerAPI(new NickNamerAPI(), this);
	}

	@Override
	public void onEnable() {
		instance = this;
		APIManager.initAPI(PacketListenerAPI.class);
		APIManager.initAPI(NickNamerAPI.class);

		Bukkit.getPluginManager().registerEvents(this, this);

		saveDefaultConfig();
		PluginAnnotations.CONFIG.loadValues(this, this);

		PluginAnnotations.COMMAND.registerCommands(this, nickCommands = new NickCommands(this));
		PluginAnnotations.COMMAND.registerCommands(this, skinCommands = new SkinCommands(this));

		switch (storageType.toLowerCase()) {
			case "local":
				getLogger().info("Using local storage");
				initStorageLocal();
				break;
			case "sql":
				getLogger().info("Using SQL storage (" + sqlUser + "@" + sqlHost + ":" + sqlPort + ")");
				initStorageSQL();
				break;
			case "redis":
				getLogger().info("Using Redis storage (" + redisHost + ":" + redisPort + ")");
				initStorageRedis();
				break;
		}
	}

	@Override
	public void onDisable() {
		APIManager.disableAPI(NickNamerAPI.class);
	}

	void initStorageLocal() {
		int nickCount = -1;
		int skinCount = -1;
		int dataCount = -1;
		try {
			nickCount = getDatabase().find(NickEntry.class).findRowCount();
			skinCount = getDatabase().find(SkinEntry.class).findRowCount();
			dataCount = getDatabase().find(SkinDataEntry.class).findRowCount();
		} catch (PersistenceException e) {
			getLogger().info("Installing database");
			installDDL();
		}
		if (nickCount > 0) {
			getLogger().info("Found " + nickCount + " player nick-data in database");
		}
		((NickManagerImpl) NickNamerAPI.getNickManager()).setNickDataProvider(new EbeanDataProvider<>(String.class, getDatabase(), NickEntry.class));
		if (dataCount > 0) {
			getLogger().info("Found " + skinCount + " player skin-data in database");
		}
		((NickManagerImpl) NickNamerAPI.getNickManager()).setSkinDataProvider(new EbeanDataProvider<>(String.class, getDatabase(), SkinEntry.class));

		if (dataCount > 0) {
			getLogger().info("Found " + dataCount + " skin textures in database");
			for (SkinDataEntry entry : getDatabase().find(SkinDataEntry.class).findSet()) {
				if (System.currentTimeMillis() - entry.getLoadTime() > 3600000/*1 hour*/) {
					getLogger().info("Deleting old skin for " + entry.getKey());
				}
			}
		}
		SkinLoader.setSkinDataProvider(new EbeanDataProvider<Object>(Object.class/*We're using a custom parser/serializer, so this class doesn't matter*/, getDatabase(), SkinDataEntry.class) {
			@Override
			public KeyValueBean newBean() {
				SkinDataEntry bean = new SkinDataEntry();
				bean.setLoadTime(System.currentTimeMillis());
				return bean;
			}
		});
	}

	void initStorageSQL() {
		if (sqlPass == null || sqlPass.isEmpty()) { sqlPass = null; }

		Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
			@Override
			public void run() {
				try (Connection connection = DriverManager.getConnection(sqlHost + ":" + sqlPort, sqlUser, sqlPass)) {
					getLogger().info("Connected to SQL");

					((NickManagerImpl) NickNamerAPI.getNickManager()).setNickDataProvider(new SQLDataProvider<>(String.class, connection, "nicknamer_data_nick"));
					((NickManagerImpl) NickNamerAPI.getNickManager()).setSkinDataProvider(new SQLDataProvider<>(String.class, connection, "nicknamer_data_skin"));
					SkinLoader.setSkinDataProvider(new SQLDataProvider<>(Object.class, connection, "nicknamer_skins"));
				} catch (SQLException e) {
					throw new RuntimeException("Failed to connect to database", e);
				}
			}
		});
	}

	void initStorageRedis() {
		if (redisPass == null || redisPass.isEmpty()) { redisPass = null; }

		Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
			@Override
			public void run() {
				JedisPoolConfig config = new JedisPoolConfig();
				config.setMaxTotal(redisMaxConnections);
				final JedisPool pool = new JedisPool(config, redisHost, redisPort, 0, redisPass);
				try (Jedis jedis = pool.getResource()) {
					jedis.ping();
					getLogger().info("Connected to Redis");

					((NickManagerImpl) NickNamerAPI.getNickManager()).setNickDataProvider(new RedisDataProvider<>(String.class, pool, "nn_data_nick:%s", "nn_data_nick:(.*)"));
					((NickManagerImpl) NickNamerAPI.getNickManager()).setSkinDataProvider(new RedisDataProvider<>(String.class, pool, "nn_data_skin:%s", "nn_data_skin:(.*)"));
					SkinLoader.setSkinDataProvider(new RedisDataProvider<Object>(Object.class, pool, "nn_skins:%s", "nn_skins:(.*)") {
						@Override
						public void put(@NonNull String key, Object value) {
							try (Jedis jedis = pool.getResource()) {
								jedis.setex(key(key), 3600, getSerializer().serialize(value));
							}
						}
					});
				} catch (JedisConnectionException e) {
					pool.destroy();
					throw new RuntimeException("Failed to connect to Redis", e);
				}
			}
		});
	}

	@Override
	public List<Class<?>> getDatabaseClasses() {
		List<Class<?>> list = new ArrayList<>();
		list.add(SkinDataEntry.class);
		list.add(NickEntry.class);
		list.add(SkinEntry.class);
		return list;
	}

	//// Replacement listeners

	@EventHandler(priority = EventPriority.LOW)
	public void on(ChatReplacementEvent event) {
		System.out.println(event);
		if (replaceChatPlayer) {
			if (NickNamerAPI.getNickManager().isNicked(event.getPlayer().getUniqueId())) {
				event.setReplacement(NickNamerAPI.getNickManager().getNick(event.getPlayer().getUniqueId()));
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void on(ChatOutReplacementEvent event) {
		replaceChatOut = true;
		System.out.println(event);
		if (replaceChatOut) {
			if (NickNamerAPI.getNickManager().isNicked(event.getPlayer().getUniqueId())) {
				event.setReplacement(NickNamerAPI.getNickManager().getNick(event.getPlayer().getUniqueId()));
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW)
	public void on(ChatInReplacementEvent event) {
		System.out.println(event);
		if (replaceChatInGeneral || replaceChatInCommand) {
			if (replaceChatInCommand && event.getContext().startsWith("/")) { // Command
				if (NickNamerAPI.getNickManager().isNicked(event.getPlayer().getUniqueId())) {
					event.setReplacement(NickNamerAPI.getNickManager().getNick(event.getPlayer().getUniqueId()));
				}
			} else if (replaceChatInGeneral) {
				if (NickNamerAPI.getNickManager().isNicked(event.getPlayer().getUniqueId())) {
					event.setReplacement(NickNamerAPI.getNickManager().getNick(event.getPlayer().getUniqueId()));
				}
			}
		}
	}

}
