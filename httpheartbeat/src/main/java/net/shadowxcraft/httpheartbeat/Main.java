package net.shadowxcraft.httpheartbeat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class Main extends JavaPlugin implements CommandExecutor {

	private HashMap<String, HttpTask> runningTasks = new HashMap<>();
	private String prefix = ChatColor.BLUE + "[" + ChatColor.GRAY + "HttpHeartBeat" + ChatColor.BLUE + "] "
			+ ChatColor.WHITE;

	@Override
	public void onEnable() {
		this.getCommand("http").setExecutor(this);
		loadFromConfig();
	}

	@Override
	public void onDisable() {
		for (HttpTask task : runningTasks.values()) {
			task.cancel();
		}
		runningTasks.clear();
	}

	class HttpTask extends BukkitRunnable {
		final URL url;
		final String method;
		final int seconds;
		private int numRetries = -1;
		private int secondsPerRetry = -1;

		public HttpTask(URL url, String method, int seconds) {
			this.url = url;
			this.method = method;
			this.seconds = seconds;
		}

		public void schedule() {
			this.runTaskTimerAsynchronously(Main.this, 20, seconds * 20);
		}

		@Override
		public void run() {
			boolean succeeded = doConnection();
			if (!succeeded && numRetries > 0) {
				new BukkitRunnable() {
					int attemptedRetries = 1;
					public void run() {
						Bukkit.getConsoleSender().sendMessage(prefix + "Attempting retry #" + attemptedRetries + ".");
						boolean succeeded = doConnection();
						if (succeeded || attemptedRetries++ == numRetries) {
							this.cancel();
							Bukkit.getConsoleSender().sendMessage(prefix + "Done with re-attempts. Continuing with the regularly scheduled pings");
						}
					}
				}.runTaskTimerAsynchronously(Main.this, secondsPerRetry * 20, secondsPerRetry * 20);
			}
		}
		
		/**
		 * @return True if it suceeded with 200 (OK). Else false.
		 */
		private boolean doConnection() {
			try {
				HttpURLConnection connection;
				connection = (HttpURLConnection) url.openConnection();
				connection.setRequestMethod(method);
				connection.setInstanceFollowRedirects(false);
				connection.connect();
				connection.disconnect();
				if (connection.getResponseCode() != 200) {
					Main.this.getLogger().warning("Received HTTP code " + connection.getResponseCode());
					return false;
				} else {
					return true;
				}
			} catch (IOException e) {
				Bukkit.getConsoleSender().sendMessage(prefix + "Could not open connection to server.");
				return false;
			}
		}
		
		private void setRetries(int secondsPerRetry, int numRetries) {
			this.secondsPerRetry = secondsPerRetry;
			this.numRetries = numRetries;
		}
	}

	private HttpTask loadTask(String name, int seconds, URL url, String method, CommandSender sender) {
		HttpTask task = new HttpTask(url, method, seconds);
		task.schedule();
		runningTasks.put(name, task);
		return task;
	}
	// Commands

	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			sendHelpMsg(sender);
			return true;
		}
		if (args[0].equalsIgnoreCase("add")) {
			if (args.length == 5) {
				final String name = args[1].toLowerCase();
				String period = args[2].toLowerCase();
				final String method = args[3].toUpperCase();
				final String address = args[4];

				if (runningTasks.containsKey(name)) {
					sender.sendMessage(prefix + "A scheduled request of that name has already been created. "
							+ "Use /http delete <name> to remove it.");
					return true;
				}

				int seconds;

				try {
					seconds = Integer.parseInt(period);
				} catch (NumberFormatException e) {
					sender.sendMessage(prefix + "Invalid second value.");
					return true;
				}

				final URL parsedUrl;
				try {
					parsedUrl = new URL(address);
				} catch (MalformedURLException e) {
					sender.sendMessage(prefix + "Invalid URL.");
					return true;
				}

				loadTask(name, seconds, parsedUrl, method, sender);

				addToConfig(name, seconds, method, address);

				sender.sendMessage(prefix + "Added");
			} else {
				sender.sendMessage(prefix + "Incorrect number of args");
				sendHelpMsg(sender);
			}
		} else if (args[0].equalsIgnoreCase("delete")) {
			if (args.length == 2) {
				String name = args[1].toLowerCase();
				HttpTask removedTask = runningTasks.remove(name);
				if (removedTask == null) {
					sender.sendMessage(prefix + "No scheduled task of that name.");
				} else {
					if (removedTask.isCancelled()) {
						sender.sendMessage(prefix + "Already canceled");
					} else {
						removedTask.cancel();
						sender.sendMessage(prefix + "Canceled");
					}
				}
				removeFromConfig(name);
			} else {
				sender.sendMessage(prefix + "Invalid Args");
				sendHelpMsg(sender);
			}
		} else if (args[0].equalsIgnoreCase("list")) {
			sender.sendMessage(prefix + "Currently scheduled requests [" + runningTasks.size() + "]:");
			for (HashMap.Entry<String, HttpTask> task : runningTasks.entrySet()) {
				sender.sendMessage(prefix + "Name: " + task.getKey() + ", period: " + task.getValue().seconds
						+ ", method: " + task.getValue().method + ", URL: " + task.getValue().url.toString());
			}
		} else if (args[0].equalsIgnoreCase("setretries" )) {
			if (args.length == 4) {
				String name = args[1].toLowerCase();
				HttpTask task = runningTasks.get(name);
				if (task != null) {
					int secondsPer, numRetries;

					try {
						numRetries = Integer.parseInt(args[2]);
					} catch (NumberFormatException e) {
						sender.sendMessage(prefix + "Invalid retries value.");
						return true;
					}
					try {
						secondsPer = Integer.parseInt(args[3]);
					} catch (NumberFormatException e) {
						sender.sendMessage(prefix + "Invalid second value.");
						return true;
					}
					if (secondsPer * numRetries >= task.seconds) {
						sender.sendMessage(prefix + "The retries take longer than the set periodic time.");
					} else if (secondsPer < 0 || numRetries < 0) {
						sender.sendMessage(prefix + "You may not use negative values for retries.");
					} else {
						setRetriesToConfig(name, secondsPer, numRetries);
						task.setRetries(secondsPer, numRetries);
						sender.sendMessage(prefix + "Set.");
					}
				} else {
					sender.sendMessage(prefix + "Could not find task. List tasks with /http list");
				}
			} else {
				sender.sendMessage(prefix + "Invalid Args");
				sendHelpMsg(sender);
			}
		} else {
			sender.sendMessage(prefix + "Unknown command.");
			sendHelpMsg(sender);
		}
		return true;
	}

	private void sendHelpMsg(CommandSender sender) {
		sender.sendMessage(prefix + "Commands:");
		sender.sendMessage(prefix + "- /http list");
		sender.sendMessage(prefix + "- /http delete <name>");
		sender.sendMessage(prefix + "- /http setretries <name> <num retries> <seconds per retry>");
		sender.sendMessage(prefix + "- /http add <name> <seconds-per> <GET|POST|HEAD|OPTIONS|PUT|DELETE|TRACE> <URL>");
	}

	// Config values
	private void loadFromConfig() {
		CommandSender sender = Bukkit.getConsoleSender();
		ConfigurationSection section = this.getConfig().getConfigurationSection("requests");
		if (section == null)
			return;

		for (String key : section.getKeys(false)) {
			final String name = key;

			ConfigurationSection sectionForRequest = section.getConfigurationSection(key);

			int seconds = sectionForRequest.getInt("seconds");
			int secondsPerRetry = sectionForRequest.getInt("seconds_per_retry", -1);
			int numRetries = sectionForRequest.getInt("num_retries", -1);
			final String method = sectionForRequest.getString("method");
			final String address = sectionForRequest.getString("URL");

			if (runningTasks.containsKey(name)) {
				sender.sendMessage(prefix + "A scheduled request of that name has already been created. "
						+ "Use /http delete <name> to remove it.");
				continue;
			}

			final URL parsedUrl;
			try {
				parsedUrl = new URL(address);
			} catch (MalformedURLException e) {
				sender.sendMessage(prefix + "Invalid URL \"" + address + "\"");
				continue;
			}

			HttpTask task = loadTask(name, seconds, parsedUrl, method, Bukkit.getConsoleSender());

			if (task != null && secondsPerRetry != -1 && numRetries != -1) {
				task.setRetries(secondsPerRetry, numRetries);
			}
		}
	}

	private void addToConfig(String name, long secondsPer, String method, String URL) {
		ConfigurationSection section = this.getConfig().createSection("requests." + name);
		section.set("seconds", secondsPer);
		section.set("method", method);
		section.set("URL", URL);
		this.saveConfig();
	}
	
	private void setRetriesToConfig(String name, long secondsPerRetry, int numRetries) {
		ConfigurationSection section = this.getConfig().getConfigurationSection("requests." + name);
		section.set("seconds_per_retry", secondsPerRetry);
		section.set("num_retries", numRetries);
		this.saveConfig();
	}

	private void removeFromConfig(String name) {
		this.getConfig().set("requests." + name, null);
		this.saveConfig();
	}

}
