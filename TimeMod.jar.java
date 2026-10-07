package com.seunome.timemod; // <-- troque pelo seu pacote

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mod de time para Forge 1.12.2
 *  - Aliados nao tomam dano uns dos outros (inclui jutsus/entidades de outros mods que tenham dono)
 *  - Aliados brilham verde, visivel apenas para quem esta no mesmo time
 *
 * Comandos: /equipe criar | convidar <jogador> | aceitar | sair | listar
 * (os times ficam na memoria: zeram ao reiniciar o servidor)
 */
@Mod(modid = TimeMod.MODID, name = "Time Mod", version = "1.0", acceptedMinecraftVersions = "[1.12.2]")
public class TimeMod {

    public static final String MODID = "timemod";
    public static final SimpleNetworkWrapper NET = NetworkRegistry.INSTANCE.newSimpleChannel(MODID);

    // jogador -> id do time
    private static final Map<UUID, String> TEAM_OF = new HashMap<>();
    // convidado -> id do time
    private static final Map<UUID, String> INVITES = new HashMap<>();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        NET.registerMessage(SyncHandler.class, SyncPacket.class, 0, Side.CLIENT);
        MinecraftForge.EVENT_BUS.register(this);
        if (e.getSide() == Side.CLIENT) {
            MinecraftForge.EVENT_BUS.register(new ClientHooks());
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent e) {
        e.registerServerCommand(new TeamCommand());
    }

    // =====================================================================
    //  LOGICA DE TIME
    // =====================================================================

    public static boolean sameTeam(Entity a, Entity b) {
        String ta = TEAM_OF.get(a.getUniqueID());
        return ta != null && ta.equals(TEAM_OF.get(b.getUniqueID()));
    }

    private static Set<UUID> members(String team) {
        Set<UUID> set = new HashSet<>();
        if (team == null) return set;
        for (Map.Entry<UUID, String> en : TEAM_OF.entrySet()) {
            if (team.equals(en.getValue())) set.add(en.getKey());
        }
        return set;
    }

    /** Envia ao jogador a lista de aliados dele (sem ele mesmo). */
    private static void sync(UUID id) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(id);
        if (p == null) return;
        Set<UUID> allies = members(TEAM_OF.get(id));
        allies.remove(id);
        NET.sendTo(new SyncPacket(allies), p);
    }

    private static void syncAll(Set<UUID> ids) {
        for (UUID id : ids) sync(id);
    }

    // =====================================================================
    //  BLOQUEIO DE DANO
    // =====================================================================

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttack(LivingAttackEvent e) {
        check(e, e.getEntityLiving(), e.getSource());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onHurt(LivingHurtEvent e) {
        check(e, e.getEntityLiving(), e.getSource());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDamage(LivingDamageEvent e) {
        check(e, e.getEntityLiving(), e.getSource());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onKnockback(LivingKnockBackEvent e) {
        EntityLivingBase victim = e.getEntityLiving();
        if (victim.world.isRemote || !(victim instanceof EntityPlayer)) return;
        EntityPlayer attacker = resolveOwner(e.getAttacker());
        if (attacker != null && attacker != victim && sameTeam(victim, attacker)) {
            e.setCanceled(true);
        }
    }

    private static void check(Event ev, EntityLivingBase victim, DamageSource src) {
        if (victim.world.isRemote || !(victim instanceof EntityPlayer) || src == null) return;
        EntityPlayer attacker = resolveOwner(src.getTrueSource());
        if (attacker == null) attacker = resolveOwner(src.getImmediateSource());
        if (attacker != null && attacker != victim && sameTeam(victim, attacker)) {
            ev.setCanceled(true);
        }
    }

    // ---- descobre o jogador "dono" de uma entidade (jutsu, clone, projetil...) ----

    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new HashMap<>();

    private static EntityPlayer resolveOwner(Entity e) {
        if (e == null) return null;
        if (e instanceof EntityPlayer) return (EntityPlayer) e;

        if (e instanceof EntityThrowable) {
            EntityLivingBase t = ((EntityThrowable) e).getThrower();
            if (t instanceof EntityPlayer) return (EntityPlayer) t;
        }
        if (e instanceof EntityArrow) {
            Entity s = ((EntityArrow) e).shootingEntity;
            if (s instanceof EntityPlayer) return (EntityPlayer) s;
        }
        if (e instanceof EntityTameable) {
            EntityLivingBase o = ((EntityTameable) e).getOwner();
            if (o instanceof EntityPlayer) return (EntityPlayer) o;
        }

        // fallback: procura por campos Entity/UUID na classe da entidade (mods como ShinobiCraft)
        for (Field f : fieldsOf(e.getClass())) {
            try {
                Object v = f.get(e);
                if (v == null || v == e) continue;
                if (v instanceof EntityPlayer) return (EntityPlayer) v;
                if (v instanceof UUID) {
                    EntityPlayer p = e.world.getPlayerEntityByUUID((UUID) v);
                    if (p != null) return p;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<Field> fieldsOf(Class<?> cls) {
        List<Field> list = FIELD_CACHE.get(cls);
        if (list != null) return list;
        list = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Entity.class && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (Entity.class.isAssignableFrom(t) || t == UUID.class) {
                    try {
                        f.setAccessible(true);
                        list.add(f);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        FIELD_CACHE.put(cls, list);
        return list;
    }

    // =====================================================================
    //  SINCRONIZACAO NO LOGIN
    // =====================================================================

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        sync(e.player.getUniqueID());
    }

    // =====================================================================
    //  COMANDO /equipe
    // =====================================================================

    public static class TeamCommand extends CommandBase {
        @Override
        public String getName() {
            return "equipe";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/equipe <criar | convidar <jogador> | aceitar | sair | listar>";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            EntityPlayerMP me = getCommandSenderAsPlayer(sender);
            if (args.length == 0) throw new WrongUsageException(getUsage(sender));
            UUID myId = me.getUniqueID();
            String myTeam = TEAM_OF.get(myId);

            switch (args[0].toLowerCase()) {
                case "criar": {
                    if (myTeam != null) {
                        msg(me, TextFormatting.RED, "Voce ja esta em uma equipe. Use /equipe sair.");
                        return;
                    }
                    TEAM_OF.put(myId, UUID.randomUUID().toString());
                    sync(myId);
                    msg(me, TextFormatting.GREEN, "Equipe criada! Use /equipe convidar <jogador>.");
                    break;
                }
                case "convidar": {
                    if (args.length < 2) throw new WrongUsageException("/equipe convidar <jogador>");
                    if (myTeam == null) {
                        msg(me, TextFormatting.RED, "Voce nao esta em uma equipe. Use /equipe criar.");
                        return;
                    }
                    EntityPlayerMP target = getPlayer(server, sender, args[1]);
                    if (TEAM_OF.containsKey(target.getUniqueID())) {
                        msg(me, TextFormatting.RED, target.getName() + " ja esta em uma equipe.");
                        return;
                    }
                    INVITES.put(target.getUniqueID(), myTeam);
                    msg(me, TextFormatting.GREEN, "Convite enviado para " + target.getName() + ".");
                    msg(target, TextFormatting.YELLOW, me.getName() + " convidou voce para a equipe. Digite /equipe aceitar.");
                    break;
                }
                case "aceitar": {
                    String invited = INVITES.remove(myId);
                    if (invited == null) {
                        msg(me, TextFormatting.RED, "Voce nao tem convites.");
                        return;
                    }
                    if (myTeam != null) {
                        msg(me, TextFormatting.RED, "Saia da sua equipe atual primeiro.");
                        return;
                    }
                    if (members(invited).isEmpty()) {
                        msg(me, TextFormatting.RED, "Essa equipe nao existe mais.");
                        return;
                    }
                    TEAM_OF.put(myId, invited);
                    syncAll(members(invited));
                    msg(me, TextFormatting.GREEN, "Voce entrou na equipe!");
                    break;
                }
                case "sair": {
                    if (myTeam == null) {
                        msg(me, TextFormatting.RED, "Voce nao esta em uma equipe.");
                        return;
                    }
                    TEAM_OF.remove(myId);
                    sync(myId);
                    syncAll(members(myTeam));
                    msg(me, TextFormatting.GREEN, "Voce saiu da equipe.");
                    break;
                }
                case "listar": {
                    if (myTeam == null) {
                        msg(me, TextFormatting.RED, "Voce nao esta em uma equipe.");
                        return;
                    }
                    StringBuilder sb = new StringBuilder("Membros: ");
                    for (UUID id : members(myTeam)) {
                        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(id);
                        sb.append(p != null ? p.getName() : "(offline)").append(", ");
                    }
                    msg(me, TextFormatting.AQUA, sb.toString());
                    break;
                }
                default:
                    throw new WrongUsageException(getUsage(sender));
            }
        }

        private static void msg(EntityPlayerMP p, TextFormatting color, String text) {
            TextComponentString c = new TextComponentString(text);
            c.getStyle().setColor(color);
            p.sendMessage(c);
        }
    }

    // =====================================================================
    //  PACOTE (servidor -> cliente): lista de aliados
    // =====================================================================

    public static class SyncPacket implements IMessage {
        public Set<UUID> ids = new HashSet<>();

        public SyncPacket() {
        }

        public SyncPacket(Set<UUID> ids) {
            this.ids = ids;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int n = buf.readInt();
            for (int i = 0; i < n; i++) ids.add(new UUID(buf.readLong(), buf.readLong()));
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(ids.size());
            for (UUID id : ids) {
                buf.writeLong(id.getMostSignificantBits());
                buf.writeLong(id.getLeastSignificantBits());
            }
        }
    }

    public static class SyncHandler implements IMessageHandler<SyncPacket, IMessage> {
        @Override
        public IMessage onMessage(SyncPacket m, MessageContext ctx) {
            ClientHooks.handle(m.ids);
            return null;
        }
    }

    // =====================================================================
    //  CLIENTE: brilho verde so para aliados
    // =====================================================================

    @SideOnly(Side.CLIENT)
    public static class ClientHooks {
        private static final Set<UUID> ALLIES = new HashSet<>();
        private static final Set<UUID> GLOWING = new HashSet<>();
        private static final String TEAM = "timemod_aliados";

        static void handle(final Set<UUID> ids) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ALLIES.clear();
                    ALLIES.addAll(ids);
                }
            });
        }

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getMinecraft();
            World w = mc.world;
            if (w == null || mc.player == null) {
                GLOWING.clear();
                return;
            }

            Scoreboard sb = w.getScoreboard();
            ScorePlayerTeam team = sb.getTeam(TEAM);
            if (team == null) {
                team = sb.createTeam(TEAM);
                team.setPrefix(TextFormatting.GREEN.toString()); // 1.12: a cor do contorno vem do prefixo
            }

            for (EntityPlayer p : w.playerEntities) {
                if (p == mc.player) continue;
                UUID id = p.getUniqueID();
                if (ALLIES.contains(id)) {
                    p.setGlowing(true); // reaplicado todo tick (o servidor pode sobrescrever a flag)
                    GLOWING.add(id);
                    if (p.getTeam() == null) sb.addPlayerToTeam(p.getName(), TEAM);
                } else if (GLOWING.remove(id)) {
                    p.setGlowing(false);
                    if (p.getTeam() == team) sb.removePlayerFromTeam(p.getName(), team);
                }
            }
        }
    }
}
