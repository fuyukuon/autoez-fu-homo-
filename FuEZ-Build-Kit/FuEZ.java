package dev.wonhung.features.modules.funny;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.wonhung.Wonhung;
import dev.wonhung.event.impl.entity.DeathEvent;
import dev.wonhung.event.impl.entity.TotemPopEvent;
import dev.wonhung.event.impl.network.DisconnectEvent;
import dev.wonhung.event.impl.network.PacketEvent;
import dev.wonhung.event.system.Subscribe;
import dev.wonhung.features.commands.Command;
import dev.wonhung.features.commands.ModuleCommand;
import dev.wonhung.features.modules.Module;
import dev.wonhung.features.settings.Setting;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_1297;
import net.minecraft.class_1309;
import net.minecraft.class_1657;
import net.minecraft.class_2561;
import net.minecraft.class_2596;
import net.minecraft.class_2663;
import net.minecraft.class_634;

public class FuEZ extends Module implements ClientModInitializer {
   private static final byte STATUS_DEATH = 3;
   private static final byte STATUS_TOTEM_POP = 35;
   private static final long POP_DEDUP_MS = 75L;
   private static final long DEATH_DEDUP_MS = 1500L;
   private static final String RANDOM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

   // -------------------- General Settings -------------------- //
   public final Setting<Float> range = this.num("Range", 25.0F, 0.0F, 50.0F).setPage("General");
   public final Setting<Integer> tickDelay = this.num("Delay", 10, 0, 100).setPage("General");
   public final Setting<Integer> maxQueue = this.num("MaxQueue", 5, 1, 20).setPage("General");
   public final Setting<Boolean> trackPlayers = this.bool("TrackPlayers", true).setPage("General");
   public final Setting<Boolean> ignoreFriends = this.bool("IgnoreFriends", true).setPage("General");
   public final Setting<Integer> antiSpam = this.num("AntiSpam", 0, 0, 16).setPage("General");
   public final Setting<Boolean> notify = this.bool("Notify", false).setPage("General");

   // -------------------- Pop Settings -------------------- //
   public final Setting<Boolean> enablePop = this.bool("PopMessage", true).setPage("Pop");
   public final Setting<String> popMessages = this.str(
      "PopMessages",
      "ez pop <NAME> <COUNT> | pop <NAME> <COUNT> | i love kiwi pop <NAME> <COUNT>"
   ).setPage("Pop");

   // -------------------- Death Settings -------------------- //
   public final Setting<Boolean> enableDeath = this.bool("DeathMessage", true).setPage("Death");
   public final Setting<Boolean> onlyTagged = this.bool("OnlyTagged", true).setPage("Death");
   public final Setting<Boolean> keepPops = this.bool("KeepPops", false).setPage("Death");
   public final Setting<String> deathMessages = this.str(
      "DeathMessages",
      "<NAME> needed Hulkenberg's nut only <COUNT> times to get Hulkenberg'd | nemtom <NAME> <COUNT>"
   ).setPage("Death");

   // -------------------- Internal State -------------------- //
   private final Map<UUID, Integer> taggedPlayers = new ConcurrentHashMap<>();
   private final Map<UUID, Long> lastPopSeenMs = new ConcurrentHashMap<>();
   private final Map<UUID, Long> lastDeathSeenMs = new ConcurrentHashMap<>();
   private final Deque<String> messageQueue = new ConcurrentLinkedDeque<>();
   private final Random random = new Random();
   private int tickTimer = 0;
   private int lastPopIdx = -1;
   private int lastDeathIdx = -1;

   public FuEZ() {
      super("FuEZ", "Upgraded GenyoAutoEZ: tracks enemy totem pops and deaths with anti-spam queue.", Module.Category.PLAYER);
      this.popMessages.setVisibility((v) -> (Boolean)this.enablePop.getValue());
      this.onlyTagged.setVisibility((v) -> (Boolean)this.enableDeath.getValue());
      this.keepPops.setVisibility((v) -> (Boolean)this.enableDeath.getValue());
      this.deathMessages.setVisibility((v) -> (Boolean)this.enableDeath.getValue());
   }

   @Override
   public void onInitializeClient() {
      try {
         if (Wonhung.moduleManager != null && Wonhung.moduleManager.getModuleByClass(FuEZ.class) == null) {
            Wonhung.moduleManager.register(this);
            if (Wonhung.commandManager != null) {
               Wonhung.commandManager.register(new ModuleCommand(this));
            }
            restoreSaved(this);
         }
      } catch (Throwable t) {
         System.err.println("[Wonhung] FuEZ failed to initialize: " + String.valueOf(t));
      }
   }

   private static void restoreSaved(Module module) {
      try {
         Path path = FabricLoader.getInstance().getGameDir().resolve("wonhung").resolve(Wonhung.moduleManager.getFileName());
         if (!Files.isRegularFile(path, new LinkOption[0])) {
            return;
         }
         JsonElement root = JsonParser.parseString(Files.readString(path));
         if (root == null || !root.isJsonObject()) {
            return;
         }
         JsonElement modJson = root.getAsJsonObject().get(module.getName());
         if (modJson != null && !modJson.isJsonNull()) {
            module.fromJson(modJson);
         }
      } catch (Throwable t) {
         System.err.println("[Wonhung] Could not restore FuEZ config: " + String.valueOf(t));
      }
   }

   @Override
   public void onEnable() {
      this.clearState();
   }

   @Override
   public void onDisable() {
      this.clearState();
   }

   @Subscribe
   public void onDisconnect(DisconnectEvent event) {
      this.clearState();
   }

   private void clearState() {
      this.taggedPlayers.clear();
      this.lastPopSeenMs.clear();
      this.lastDeathSeenMs.clear();
      this.messageQueue.clear();
      this.tickTimer = 0;
   }

   @Override
   public String getDisplayInfo() {
      int tracked = this.taggedPlayers.size();
      return tracked > 0 ? String.valueOf(tracked) : null;
   }

   @Override
   public void onTick() {
      if (nullCheck()) {
         this.messageQueue.clear();
         this.tickTimer = 0;
         return;
      }

      if (this.tickTimer > 0) {
         --this.tickTimer;
      }

      if (this.tickTimer <= 0 && !this.messageQueue.isEmpty()) {
         String nextMsg = this.messageQueue.pollFirst();
         if (nextMsg != null && !nextMsg.isBlank()) {
            this.dispatchChatMessage(nextMsg);
            this.tickTimer = Math.max(0, (Integer)this.tickDelay.getValue());
         }
      }
   }

   @Subscribe
   public void onTotemPop(TotemPopEvent event) {
      if (this.isEnabled() && !nullCheck()) {
         class_1309 entity = event.getEntity();
         if (entity instanceof class_1657) {
            this.handleTotemPop((class_1657)entity);
         }
      }
   }

   @Subscribe
   public void onEntityDeath(DeathEvent event) {
      if (this.isEnabled() && !nullCheck()) {
         class_1309 entity = event.getEntity();
         if (entity instanceof class_1657) {
            this.handleDeath((class_1657)entity);
         }
      }
   }

   @Subscribe
   public void onPacketReceive(PacketEvent.Receive event) {
      if (this.isEnabled() && !nullCheck()) {
         class_2596<?> raw = event.getPacket();
         if (raw instanceof class_2663) {
            class_2663 packet = (class_2663)raw;
            byte status = packet.method_11470();
            if (status == STATUS_TOTEM_POP || status == STATUS_DEATH) {
               class_1297 entity = packet.method_11469(mc.field_1687);
               if (entity instanceof class_1657) {
                  class_1657 player = (class_1657)entity;
                  if (status == STATUS_TOTEM_POP) {
                     this.handleTotemPop(player);
                  } else if (status == STATUS_DEATH) {
                     this.handleDeath(player);
                  }
               }
            }
         }
      }
   }

   private void handleTotemPop(class_1657 player) {
      if (!this.isValidEnemy(player, true)) {
         return;
      }

      UUID uuid = player.method_5667();
      if (uuid == null) {
         return;
      }

      long now = System.currentTimeMillis();
      Long prevPop = this.lastPopSeenMs.put(uuid, now);
      if (prevPop != null && now - prevPop < POP_DEDUP_MS) {
         return;
      }

      // Player is alive since they just popped a totem
      this.lastDeathSeenMs.remove(uuid);

      String name = getPlayerName(player);
      if ((Boolean)this.trackPlayers.getValue()) {
         int count = this.taggedPlayers.merge(uuid, 1, Integer::sum);
         if ((Boolean)this.enablePop.getValue()) {
            this.queuePopMessage(name, count);
         }
      } else {
         this.taggedPlayers.put(uuid, 1);
         if ((Boolean)this.enablePop.getValue()) {
            this.queuePopMessage(name, 0);
         }
      }
   }

   private void handleDeath(class_1657 player) {
      if (!(Boolean)this.enableDeath.getValue()) {
         return;
      }

      UUID uuid = player.method_5667();
      if (uuid == null) {
         return;
      }

      // Deduplicate continuous DeathEvent ticks during 20-tick death animation
      long now = System.currentTimeMillis();
      Long prevDeath = this.lastDeathSeenMs.put(uuid, now);
      if (prevDeath != null && now - prevDeath <= DEATH_DEDUP_MS) {
         return;
      }

      boolean checkRange = !(Boolean)this.onlyTagged.getValue();
      if (!this.isValidEnemy(player, checkRange)) {
         return;
      }

      boolean isTagged = this.taggedPlayers.containsKey(uuid);
      if ((Boolean)this.onlyTagged.getValue() && !isTagged) {
         return;
      }

      // Read pop count BEFORE resetting so KeepPops=false still reports actual pops
      int pops = this.taggedPlayers.getOrDefault(uuid, 0);
      if (!(Boolean)this.keepPops.getValue()) {
         this.taggedPlayers.remove(uuid);
      }

      this.queueDeathMessage(getPlayerName(player), pops);
   }

   private boolean isValidEnemy(class_1657 player, boolean enforceRange) {
      if (player == null || player == mc.field_1724) {
         return false;
      }
      if ((Boolean)this.ignoreFriends.getValue() && Wonhung.friendManager != null && Wonhung.friendManager.isFriend(player)) {
         return false;
      }
      if (enforceRange) {
         float maxRange = (Float)this.range.getValue();
         if (maxRange > 0.0F && mc.field_1724.method_5739(player) > maxRange) {
            return false;
         }
      }
      return true;
   }

   private static String getPlayerName(class_1657 player) {
      class_2561 text = player.method_5477();
      return text != null ? text.getString() : "Player";
   }

   private void queuePopMessage(String name, int count) {
      List<String> pool = parseMessageList((String)this.popMessages.getValue());
      if (pool.isEmpty()) {
         return;
      }

      int idx = this.pickNonRepeatingIndex(pool.size(), this.lastPopIdx);
      this.lastPopIdx = idx;
      String template = pool.get(idx);
      String countStr = count > 0 ? "+" + count : "+1";
      String formatted = applyPlaceholders(template, name, countStr);
      this.enqueueMessage(formatted);
   }

   private void queueDeathMessage(String name, int pops) {
      List<String> pool = parseMessageList((String)this.deathMessages.getValue());
      if (pool.isEmpty()) {
         return;
      }

      int idx = this.pickNonRepeatingIndex(pool.size(), this.lastDeathIdx);
      this.lastDeathIdx = idx;
      String template = pool.get(idx);
      String formatted = applyPlaceholders(template, name, String.valueOf(pops));
      this.enqueueMessage(formatted);
   }

   private void enqueueMessage(String message) {
      String finalMsg = message + this.buildRandomSuffix((Integer)this.antiSpam.getValue());
      int limit = Math.max(1, (Integer)this.maxQueue.getValue());
      while (this.messageQueue.size() >= limit) {
         this.messageQueue.pollFirst();
      }
      this.messageQueue.offerLast(finalMsg);
   }

   private void dispatchChatMessage(String message) {
      class_634 handler = mc.method_1562();
      if (handler != null) {
         handler.method_45729(message);
      }
      if ((Boolean)this.notify.getValue()) {
         Command.sendMessage("FuEZ → " + message);
      }
   }

   private int pickNonRepeatingIndex(int size, int lastIdx) {
      if (size <= 1) {
         return 0;
      }
      int idx = this.random.nextInt(size);
      if (idx == lastIdx) {
         idx = (idx + 1 + this.random.nextInt(size - 1)) % size;
      }
      return idx;
   }

   private static List<String> parseMessageList(String raw) {
      List<String> result = new ArrayList<>();
      if (raw == null || raw.isBlank()) {
         return result;
      }
      String[] parts = raw.split("\\|");
      for (String part : parts) {
         String trimmed = part.trim();
         if (!trimmed.isEmpty()) {
            result.add(trimmed);
         }
      }
      return result;
   }

   private static String applyPlaceholders(String template, String name, String countStr) {
      return template
         .replace("<NAME>", name)
         .replace("<name>", name)
         .replace("%player%", name)
         .replace("{name}", name)
         .replace("<COUNT>", countStr)
         .replace("<count>", countStr)
         .replace("%pops%", countStr)
         .replace("%count%", countStr)
         .replace("{count}", countStr);
   }

   private String buildRandomSuffix(int length) {
      if (length <= 0) {
         return "";
      }
      StringBuilder sb = new StringBuilder(length + 1);
      sb.append(' ');
      for (int i = 0; i < length; ++i) {
         sb.append(RANDOM_CHARS.charAt(this.random.nextInt(RANDOM_CHARS.length())));
      }
      return sb.toString();
   }
}
