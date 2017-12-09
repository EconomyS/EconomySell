package me.onebone.economysell;

/*
 * EconomySell: A plugin which allows your server to create sell centers
 * Copyright (C) 2016  onebone <jyc00410@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.nukkit.math.BlockFace;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import me.onebone.economyapi.EconomyAPI;
import me.onebone.economysell.provider.Provider;
import me.onebone.economysell.provider.YamlProvider;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.SignChangeEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerTeleportEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.TextFormat;
import cn.nukkit.utils.Utils;

public class EconomySell extends PluginBase implements Listener{
	private Provider provider = null;
	private Map<String, Sell> sells;
	private Map<String, Object[]> queue;
	private Map<String, String> lang;
	private Map<Player, Object[]> taps;
	
	private Map<Level, List<ItemDisplayer>> displayers = null;
	
	private EconomyAPI api;
	
	public String getMessage(String key){
		return this.getMessage(key, new String[]{});
	}
	
	public String getMessage(String key, Object[] params){
		if(this.lang.containsKey(key)){
			return replaceMessage(this.lang.get(key), params);
		}
		return "Could not find message with " + key;
	}
	
	private String replaceMessage(String lang, Object[] params){
		StringBuilder builder = new StringBuilder();
		
		for(int i = 0; i < lang.length(); i++){
			char c = lang.charAt(i);
			if(c == '{'){
				int index;
				if((index = lang.indexOf('}', i)) != -1){
					try{
						String p = lang.substring(i + 1, index);
						if(p.equals("M")){
							i = index;
							
							builder.append(api.getMonetaryUnit());
							continue;
						}
						int param = Integer.parseInt(p);
						
						if(params.length > param){
							i = index;
							
							builder.append(params[param]);
							continue;
						}
					}catch(NumberFormatException e){}
				}
			}
			
			builder.append(c);
		}
		
		return TextFormat.colorize(builder.toString());
	}
	
	@SuppressWarnings("unchecked")
	public void onEnable(){
		this.saveDefaultConfig();
		
		String name = this.getConfig().get("language", "en");
		InputStream is = this.getResource("lang_" + name + ".json");
		if(is == null){
			this.getLogger().critical("Could not load language file. Changing to default.");
			
			is = this.getResource("lang_en.json");
		}
		
		try{
			lang = new GsonBuilder().create().fromJson(Utils.readFile(is), new TypeToken<LinkedHashMap<String, String>>(){}.getType());
		}catch(JsonSyntaxException | IOException e){
			this.getLogger().critical(e.getMessage());
		}
		
		if(!name.equals("en")){
			try{
				LinkedHashMap<String, String> temp = new GsonBuilder().create().fromJson(Utils.readFile(this.getResource("lang_en.json")), new TypeToken<LinkedHashMap<String, String>>(){}.getType());
				temp.forEach((k, v) -> {
					if(!lang.containsKey(k)){
						lang.put(k, v);
					}
				});
			}catch(IOException e){
				this.getLogger().critical(e.getMessage());
			}
		}
		
		api = EconomyAPI.getInstance();
		
		this.queue = new HashMap<>();
		this.displayers = new HashMap<>(); 
		this.taps = new HashMap<>();
		
		this.provider = new YamlProvider(this);
		
		sells = new HashMap<>();
		this.provider.getAll().forEach((k, v) -> {
			List<Object> val = (List<Object>)v;
			Sell sell = new Sell(this.getServer(), (int)val.get(0), (int)val.get(1), (int)val.get(2), (String)val.get(3),
					Item.get((int)val.get(4), (int)val.get(5), (int)val.get(7)),
					(double)val.get(8),
					(int)val.get(9));
			
			Position pos = sell.getPosition();
			sells.put(pos.x + ":" + pos.y + ":" + pos.z + ":" + sell.getLevelName(), sell);
			
			if(sell.getDisplayer() != null && sell.getPosition().getLevel() != null){ // item display option is 'none'
				if(!displayers.containsKey(sell.getPosition().getLevel())){
					displayers.put(sell.getPosition().getLevel(), new ArrayList<ItemDisplayer>());
				}
				
				displayers.get(sell.getPosition().getLevel()).add(sell.getDisplayer());
			}
		});
		
		this.getServer().getPluginManager().registerEvents(this, this);
	}
	
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
		if(command.getName().equals("sell")){
			if(args.length < 1){
				sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
				return true;
			}
			
			if(args[0].equals("create")){
				if(!(sender instanceof Player)){
					sender.sendMessage(TextFormat.RED + "Please run this command in-game.");
					return true;
				}
				
				if(args.length < 4){
					sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
					return true;
				}
				
				int side = -1;
				if(args.length > 4){
					try{
						side = Integer.parseInt(args[4]);
					}catch(NumberFormatException e){
						switch(args[4].toLowerCase()){
						case "down": side = BlockFace.DOWN.getIndex(); break;
						case "east": side = BlockFace.EAST.getIndex(); break;
						case "north": side = BlockFace.NORTH.getIndex(); break;
						case "south": side = BlockFace.SOUTH.getIndex(); break;
						case "up": side = BlockFace.UP.getIndex(); break;
						case "west": side = BlockFace.WEST.getIndex(); break;
						case "sell": side = -1; break;
						case "none": side = -2; break;
						default:
							sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
							return true;
						}
					}
				}
				
				if(side < -2 && side > 5){
					sender.sendMessage(this.getMessage("invalid-side"));
					return true;
				}
				
				try{
					int amount = Integer.parseInt(args[2]);
					float price = Float.parseFloat(args[3]);
					
					Item item = Item.fromString(args[1]);
					item.setCount(amount);
					queue.put(sender.getName().toLowerCase(), new Object[]{
						true, item, side, price
					});
					
					sender.sendMessage(this.getMessage("added-queue"));
				}catch(NumberFormatException e){
					sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
				}
				return true;
			}else if(args[0].equals("remove")){
				if(!(sender instanceof Player)){
					sender.sendMessage(TextFormat.RED + "Please run this command in-game.");
					return true;
				}
				
				queue.put(sender.getName().toLowerCase(), new Object[]{
					false
				});
				sender.sendMessage(this.getMessage("added-rm-queue"));
			}else{
				sender.sendMessage(TextFormat.RED + "Usage: " + command.getUsage());
			}
		}else if(command.getName().equals("vend")){
			if(!(sender instanceof Player)){
				sender.sendMessage(TextFormat.RED + "Please run this command in-game.");
				return true;
			}
			
			if(!this.getConfig().getBoolean("sell.enable-vend", true)){
				sender.sendMessage(this.getMessage("vending-not-enabled"));
				return true;
			}
			
			Player player = (Player) sender;
			if(this.taps.containsKey(player)){
				Object[] tap = this.taps.get(player);
				Item item = ((Item) tap[1]).clone();
				
				double price = (double) tap[2];
				int amount = item.getCount();
				
				if(args.length > 0){
					try{
						amount = Integer.parseInt(args[0]);
					}catch(NumberFormatException e){
						sender.sendMessage(this.getMessage("invalid-message"));
						return true;
					}
				}
				
				if(amount % item.getCount() == 0){
					price = price * (amount / item.getCount());
					item.setCount(amount);
					
					if(player.hasPermission("economysell.sell")){
						if(!player.getInventory().contains(item)){
							player.sendMessage(this.getMessage("no-item", new Object[]{item.getName()}));
							return true;
						}

						if (player.isCreative()) {
							player.sendMessage(this.getMessage("no-player"));
							return true;
						}
						
						this.api.addMoney(player, price, true);
						player.getInventory().removeItem(item);
						player.sendMessage(this.getMessage("sold-item", new Object[]{
								item.getName(), item.getCount(), price
						}));
					}else{
						player.sendMessage(this.getMessage("no-permission-sell"));
					}
				}else{
					player.sendMessage(this.getMessage("invalid-amount-multiple", new Object[]{item.getCount()}));
				}
				
				this.taps.remove(player);
			}else{
				player.sendMessage(this.getMessage("select-sell"));
			}
			return true;
		}
		return false;
	}
	
	@EventHandler
	public void onTouch(PlayerInteractEvent event){
		if(event.getAction() == PlayerInteractEvent.Action.LEFT_CLICK_AIR || event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_AIR) return;
		
		Player player = event.getPlayer();
		Position pos = (Position)event.getBlock();
		
		String key = pos.x + ":" + pos.y + ":" + pos.z + ":" + pos.level.getFolderName();
		
		if(queue.containsKey(player.getName().toLowerCase())){
			event.setCancelled();
			
			Object[] info = queue.get(player.getName().toLowerCase());
			
			if((boolean) info[0]){
				if(!this.sells.containsKey(key)){
					this.provider.addSell(pos, (Item)info[1], (float) info[3], (int) info[2]);
					
					Sell sell = new Sell(pos, pos.level.getFolderName(), (Item)info[1], (float) info[3], (int) info[2]);
					
					this.sells.put(key, sell);
					
					if(sell.getDisplayer() != null){
						if(!this.displayers.containsKey(pos.level)){
							this.displayers.put(pos.level, new ArrayList<ItemDisplayer>());
						}
						this.displayers.get(pos.level).add(sell.getDisplayer());
					
						sell.getDisplayer().spawnToAll(sell.getPosition().getLevel());
					}
					
					queue.remove(player.getName().toLowerCase());
					
					player.sendMessage(this.getMessage("sell-created"));
				}else{
					player.sendMessage(this.getMessage("sell-already-exist"));
				}
			}else{
				if(this.sells.containsKey(key)){
					this.provider.removeSell(pos);
					
					Sell sell = this.sells.get(key);
					
					if(sell.getDisplayer() != null){
						if(this.displayers.containsKey(pos.level)){
							this.displayers.get(pos.level).remove(sell.getDisplayer());
						}
					}
					this.sells.remove(key);
					
					queue.remove(player.getName().toLowerCase());
					
					player.sendMessage(this.getMessage("sell-removed"));
				}
			}
		}else{
			Sell sell = this.sells.get(key);
			
			if(sell != null){
				event.setCancelled();
				
				Item item = sell.getItem();
				
				if(this.getConfig().get("sell.tap-twice", true)){
					long now = System.currentTimeMillis();
					
					Object[] tap = this.taps.get(player);
					if(!this.taps.containsKey(player) || now - ((Long) tap[0]) > 1000){
						player.sendMessage(this.getMessage("tap-again", new Object[]{
								item.getName(), item.getCount(), sell.getPrice()
						}));
						
						this.taps.put(player, new Object[]{
							now, item, sell.getPrice()
						});
						return;
					}else{
						this.taps.remove(player);
					}
				}
				
				if(player.hasPermission("economysell.sell")){
					if(!player.getInventory().contains(item)){
						player.sendMessage(this.getMessage("no-item", new Object[]{item.getName()}));
						return;
					}

					if (player.isCreative()) {
						player.sendMessage(this.getMessage("no-player"));
						return;
					}
					
					this.api.addMoney(player, sell.getPrice(), true);
					player.getInventory().removeItem(item);
					player.sendMessage(this.getMessage("sold-item", new Object[]{
							item.getName(), item.getCount(), sell.getPrice()
					}));
				}else{
					player.sendMessage(this.getMessage("no-permission-sell"));
				}
			}
		}
	}
	
	@EventHandler
	public void onJoin(PlayerJoinEvent event){
		Player player = event.getPlayer();
		
		if(this.displayers.containsKey(player.getLevel())){
			this.displayers.get(player.getLevel()).forEach(displayer -> displayer.spawnTo(player));
		}
	}
	
	@EventHandler
	public void onBreak(BlockBreakEvent event) {
		Position pos = event.getBlock();
		String key = pos.x + ":" + pos.y + ":" + pos.z + ":" + pos.level.getFolderName();

		if(this.sells.containsKey(key)){
			if(!event.getPlayer().hasPermission("economysell.break")) {
				event.getPlayer().sendMessage(this.getMessage("sell-breaking-forbidden"));
				event.setCancelled();
				return;
			}
			
			if(this.getConfig().getBoolean("destroy.break", true)){
				Sell shop = this.sells.get(key);
				
				if(shop.getDisplayer() != null){
					if(this.displayers.containsKey(pos.level)){
						this.displayers.get(pos.level).remove(shop.getDisplayer());
					}
				}
				this.sells.remove(key);
				this.provider.removeSell(pos);
				
				event.getPlayer().sendMessage(this.getMessage("sell-removed"));
			}
		}
	}

	@EventHandler
	public void onSignChange(SignChangeEvent event) {
		String[] lines = event.getLines();

		if (lines[0].equalsIgnoreCase("sell") || lines[0].equalsIgnoreCase("[sell]")) {
			Position pos = event.getBlock();
			String key = pos.x + ":" + pos.y + ":" + pos.z + ":" + pos.level.getFolderName();
			if(!this.sells.containsKey(key)){
				Player player = event.getPlayer();
				
				if(player.hasPermission("economysell.create")){
					float price;
					int amount;
					
					try{
						price = Float.parseFloat(lines[1]);
						amount = Integer.parseInt(lines[3]);
					}catch(NumberFormatException e){
						player.sendMessage(this.getMessage("invalid-format"));
						return;
					}
					
					Item item = Item.fromString(lines[2]);
					item.setCount(amount);
					
					this.provider.addSell(pos, item, price, -2);
					
					Sell sell = new Sell(pos, pos.level.getFolderName(), item, price, -2);
					
					this.sells.put(key, sell);
					
					event.setLine(0, TextFormat.LIGHT_PURPLE+this.getMessage("sign-text-1"));
					event.setLine(1, TextFormat.YELLOW+this.getMessage("sign-text-2", new Object[]{price}));
					event.setLine(2, TextFormat.WHITE+this.getMessage("sign-text-3", new Object[]{item.getName()}));
					event.setLine(3, TextFormat.AQUA+this.getMessage("sign-text-4", new Object[]{amount}));
					
					player.sendMessage(this.getMessage("sell-created"));
				}
			}
		}
	}
	
	@EventHandler
	public void onTeleport(PlayerTeleportEvent event){
		Position from = event.getFrom();
		Position to = event.getTo();
		
		if(from.getLevel() != to.getLevel()){
			this.display(event.getPlayer(), from, to);
			/*if(this.displayers.containsKey(from.getLevel())){
				this.displayers.get(from.getLevel()).forEach((displayer) -> displayer.despawnFrom(player));
			}
			
			if(this.displayers.containsKey(to.getLevel())){
				this.displayers.get(to.getLevel()).forEach((displayer) -> displayer.spawnTo(player));
			}*/
		}
	}

	@EventHandler
	public void onPlayerMove(PlayerMoveEvent event){
		Position from = event.getFrom();
		Position to = event.getTo();
		
		if(from.getLevel() == to.getLevel()
			&& ((int) from.x >> 4 != (int) to.x >> 4
			|| (int) from.z >> 4 != (int) to.z >> 4)){

			this.display(event.getPlayer(), from, to);
		}
	}

	private void display(Player player, Position from, Position to){
		if(from != null && this.displayers.containsKey(to.getLevel())){
			this.displayers.get(from.getLevel()).forEach(d -> {
				if(Math.abs((int) from.x >> 4 - (int) d.getPosition().x >> 4) <= 1
				&& Math.abs((int) from.z >> 4 - (int) d.getPosition().z >> 4) <= 1){
					d.despawnFrom(player);
				}
			});
		}
		
		if(this.displayers.containsKey(to.getLevel())){
			this.displayers.get(to.getLevel()).forEach(d -> {
				if(Math.abs((int) to.x >> 4 - (int) d.getPosition().x >> 4) <= 1
				&& Math.abs((int) to.z >> 4 - (int) d.getPosition().z >> 4) <= 1){
					d.spawnTo(player);
				}
			});
		}
	}
	
	public void onDisable(){
		if(provider != null){
			this.provider.close();
		}
	}
}
