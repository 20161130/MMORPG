package cyou.mrd.game.mail;

import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.procedure.TIntObjectProcedure;
import gnu.trove.procedure.TIntProcedure;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cyou.mrd.ObjectAccessor;
import cyou.mrd.Platform;
import cyou.mrd.data.Data;
import cyou.mrd.data.DataKeys;
import cyou.mrd.entity.Player;
import cyou.mrd.event.Event;
import cyou.mrd.event.GameEvent;
import cyou.mrd.event.OPEvent;
import cyou.mrd.game.actor.Actor;
import cyou.mrd.game.actor.ActorCacheService;
import cyou.mrd.io.OP;
import cyou.mrd.io.OPHandler;
import cyou.mrd.io.Packet;
import cyou.mrd.io.http.HOpCode;
import cyou.mrd.io.http.HSession;
import cyou.mrd.io.http.JSONPacket;
import cyou.mrd.projectdata.Template;
import cyou.mrd.projectdata.TextDataService;
import cyou.mrd.service.HarmoniousService;
import cyou.mrd.service.MailTemplate;
import cyou.mrd.service.PlayerService;
import cyou.mrd.util.DefaultThreadPool;
import cyou.mrd.util.ErrorHandler;
import cyou.mrd.util.Utils;

@OPHandler(TYPE = OPHandler.HTTP_EVENT)
public class DefaultMailService implements MailService<Mail> {
	private static int[] SERVER_LANG_INFO = { 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

	private static final Logger log = LoggerFactory.getLogger(DefaultMailService.class);

	protected BlockingQueue<Mail> pendingMails = new LinkedBlockingQueue<Mail>();

	private final long MAIL_VALID_TIME = 30 * 24 * 60 * 60 * 1000L;// 过期时间

	private final long MAIL_SWEEP_SECONDS = 24 * 60 * 60 * 1000L;

	protected volatile boolean sweep = false;

	public static int MAIL_TYPE_PLAYER = 0;// 玩家邮件

	public static int MAIL_TYPE_SYSTEM = 1;// 系统邮件

	public static int MAIL_TYPE_NPC = 2;// 系统好友互动消息

	public static final int MAIL_STATE_NOTREAD = 0;// 邮件状态 未读

	public static final int MAIL_STATE_ALREADYREAD = 1;// 邮件状态 已读

	protected static final int HANDLE_ONETIME = 1000; // 每次处理条数

	public static final String PROPERTY_MAILLNUM_DAY = "property_mailnum_day";

	private HashMap<Integer, MailTemplate> mailTemplates = new HashMap<Integer, MailTemplate>();

	public static int PLAYER_MAXMAILNUM_DAY = Platform.getConfiguration().getInt("player_maxmailnum_day");// 10;//玩家每日可发邮件数

	private static final boolean SWITCH_MAIL_VALID = false;// 是否开启邮件过期功能

	/**
	 * 好友普通消息
	 */
	public static final int MAIL_USERTYPE_FRIENDMESSAGE = 0;
	/**
	 * 加好友申请
	 */
	public static final int MAIL_USERTYPE_ADDFRIEND = 1;
	/**
	 * 其它
	 */
	public static final int MAIL_USERTYPE_OTHER = 2;
	/**
	 * gm
	 */
	public static final int MAIL_USERTYPE_GM = 3;

	/**
	 * 邮件列表最大邮件数
	 */
	public static final int MAIL_LIST_MAXSIZE = 200;

	public String getId() {
		return "MailServiceEx";
	}

	public void startup() throws Exception {
		Thread daemonMail = new Thread(new DaemonSendMail(), "Daemon-Send-Mail");
		daemonMail.setPriority(DefaultThreadPool.DEFAULT_THREAD_PRIORITY);
		daemonMail.start();

		if (SWITCH_MAIL_VALID) {
			Platform.getScheduler().scheduleAtFixedRate(new SweepObsoleteMail(), 0, MAIL_SWEEP_SECONDS, TimeUnit.SECONDS);
		}
		// 加载邮件模板到容器中
		Hashtable<Integer, Template> templates = Platform.getAppContext().get(TextDataService.class).getTemplates(MailTemplate.class);
		if (templates != null && templates.size() > 0) {
			log.info("[Mail] [mail templates loaded] size({})", templates.size());
			Iterator<Template> it = templates.values().iterator();
			while (it.hasNext()) {
				MailTemplate t = (MailTemplate) it.next();
				log.info("MailTemplate :{}", t);
				mailTemplates.put(t.getId(), t);
			}
		} else {
			log.info("[Mail] [mail templates] [error] mailTemplates[null{}] or isEmpty", templates == null);
		}

		// 收集系统公告邮件
		Platform.getScheduler().scheduleAtFixedRate(new SystemNoticeMail(), 0, 100, TimeUnit.SECONDS);
	}

	public void shutdown() throws Exception {

	}

	@OPEvent(eventCode = GameEvent.EVENT_PLAYER_LOGINED)
	public void resetPalyerMailNum(Event event) {
		Player player = (Player) event.param1;
		if (player.getPool().getInt(PROPERTY_MAILLNUM_DAY, 0) != 0) {
			Calendar ca1 = Calendar.getInstance();
			Calendar ca2 = Calendar.getInstance();
			if (player.getLastLogoutTime() == null) {
				player.setLastLogoutTime(new Date());
			}
			ca2.setTime(player.getLastLogoutTime());
			if (ca1.get(Calendar.DAY_OF_YEAR) != ca2.get(Calendar.DAY_OF_YEAR)) {
				player.getPool().setInt(PROPERTY_MAILLNUM_DAY, 0);
			}
		}
	}

	// 清理过期邮件时使用
	public void deleteMail(Mail mail) throws MailException {
		log.info("[Mail] delete({}), content:{}", mail.getId(), mail.getContent());
		Platform.getEntityManager().deleteSync(mail);
		log.info("[Mail] deleteMail end:success");
	}

	// 守护进程调用发邮件
	public void sendMail(Mail mail) {
		log.info("[Mail] sendMail(SourceId({})), content:{}", mail.getSourceId(), mail.getContent());
		if (mail.getType() != MAIL_TYPE_SYSTEM) {
			String content = Platform.getAppContext().get(HarmoniousService.class).filterBadWords(mail.getContent());
			mail.setContent(content);
			log.info("[Mail] HarmoniousService sendMail(SourceId({})), content:{}", mail.getSourceId(), mail.getContent());
		}
		mail.setPostTime(new Date());
		mail.setExpirationTime(new Date(System.currentTimeMillis() + MAIL_VALID_TIME));
		Platform.getEntityManager().createSync(mail);
		Platform.getAppContext().get(PlayerService.class).notifyReceiveNewMail(mail.getDestId());
		log.info("[Mail] sendMail end:success playerId:{},destId:{}", mail.getSourceId(), mail.getDestId());
	}

	// 普通系统邮件 sourceId = -1
	public void sendSystemMail(int destId, int mailTemplateId, int language, String sourceName, String destName, int useType) {
		Mail mail = new Mail();
		MailService<?> service = Platform.getAppContext().get(MailService.class);
		mail.setContent(service.getMailContent(language, sourceName, destName, mailTemplateId));
		mail.setDestId(destId);
		mail.setPostTime(new Date());
		mail.setSourceId(-1);
		mail.setSourceName(getSystemWord(language));
		mail.setStatus(0);
		mail.setUseType(useType);
		mail.setType(MAIL_TYPE_SYSTEM);
		mail.setExpirationTime(new Date(System.currentTimeMillis() + MAIL_VALID_TIME));
		pendingMails.add(mail);
		log.info(
				"[Mail] sendSystemMailNoFilter():end destId:{},mailTemplateId:{},language:{},sourceName:{},destName:{},useType:{},playerId:{}",
				new Object[] { destId, mailTemplateId, language, sourceName, destName, useType, destId });
	}

	// 用于处理需要回传发件人的系统邮件 destId!=-1 eg.加好友邮件
	public void sendSystemMailNoFilter(int sourceId, int destId, int mailTemplateId, int language, String sourceName, String destName,
			int useType) {
		Mail mail = new Mail();
		MailService<?> service = Platform.getAppContext().get(MailService.class);
		mail.setContent(service.getMailContent(language, sourceName, destName, mailTemplateId));
		mail.setDestId(destId);
		mail.setPostTime(new Date());
		mail.setSourceId(sourceId);
		mail.setSourceName(getSystemWord(language));
		mail.setStatus(0);
		mail.setUseType(useType);
		mail.setType(MAIL_TYPE_SYSTEM);
		mail.setExpirationTime(new Date(System.currentTimeMillis() + MAIL_VALID_TIME));
		pendingMails.add(mail);
		log.info(
				"[Mail] sendSystemMailNoFilter():end destId:{},mailTemplateId:{},language:{},sourceName:{},destName:{},useType:{},playerId:{}",
				new Object[] { destId, mailTemplateId, language, sourceName, destName, useType, destId });
	}

	// 普通系统邮件 sourceId = -1
	public void sendNpcMail(int destId, int mailTemplateId, int language, String sourceName, String destName, int useType) {
		Mail mail = new Mail();
		MailService<?> service = Platform.getAppContext().get(MailService.class);
		mail.setContent(service.getMailContent(language, sourceName, destName, mailTemplateId));
		mail.setDestId(destId);
		mail.setPostTime(new Date());
		mail.setSourceId(-1);
		mail.setSourceName(getSystemWord(language));
		mail.setStatus(0);
		mail.setUseType(useType);
		mail.setType(MAIL_TYPE_NPC);
		mail.setExpirationTime(new Date(System.currentTimeMillis() + MAIL_VALID_TIME));
		pendingMails.add(mail);
		log.info(
				"[Mail] sendNpcMailNoFilter():end destId:{},mailTemplateId:{},language:{},sourceName:{},destName:{},useType:{},playerId:{}",
				new Object[] { destId, mailTemplateId, language, sourceName, destName, useType, destId });
	}

	// 玩家间发邮件接口
	public boolean sendMail(int destId, String content, Player player, int useType) {
		log.info("[Mail] [DefaultMailService] sendMail(destId:{},content:{},playerId:{},useType:{})", new Object[] { destId, content,
				player.getInstanceId(), useType });
		Actor actor = Platform.getAppContext().get(ActorCacheService.class).findActor(destId);
		if (actor != null && player != null) {
			Mail mail = new Mail(player.getId(), actor.getId(), player.getName(), player.getIcon(), player.getLevel(), content, useType);
			mail.setType(MAIL_TYPE_PLAYER);
			pendingMails.add(mail);
			log.info("[Mail] sendMail end:success");
			return true;
		} else {
			log.info("[Mail] sendMail failed:[destPlayer is null]");
			return false;
		}
	}

	class DaemonSendMail implements Runnable {
		public void run() {
			while (true) {
				try {
					Mail mail = pendingMails.take();
					sendMail(mail);
				} catch (Exception e) {
					log.error(e.toString(), e);
				}
			}
		}
	}

	class SweepObsoleteMail implements Runnable {
		public void run() {
			sweep();
		}
	}

	class SystemNoticeMail implements Runnable {
		public void run() {
			try {
				updateSystemNotice();
			} catch (Throwable e) {
				log.error("updateSystemNotice", e);
			}
		}
	}

	/**
	 * 清理过期邮件
	 */
	protected synchronized void sweep() {
		if (sweep)
			return;
		sweep = true;
		List<Mail> mails = getObsoleteMailList(HANDLE_ONETIME);
		int size = mails.size();
		for (Mail mail : mails) {
			try {
				deleteMail(mail);
			} catch (MailException e) {
				log.error(e.toString(), e);
			}
		}
		sweep = false;
		log.info("[Mail] sweep size:{}", size);
	}

	/**
	 * 获取指定数量的过期邮件
	 * 
	 * @param limit
	 * @return
	 */
	protected List<Mail> getObsoleteMailList(int limit) {
		Date now = new Date();
		List<Mail> mails = null;
		mails = Platform.getEntityManager().limitQuery("from Mail m where m.type !=? and m.expirationTime <= ?)", 0, limit,
				MAIL_TYPE_PLAYER, now);
		log.info("[Mail] getObsoleteMailList return[mails(size:{})]", mails == null ? 0 : mails.size());
		return mails;
	}

	@OP(code = HOpCode.MAIL_SEND_CLIENT)
	public void playerSendMail(Packet packet, HSession session) throws UnsupportedEncodingException {
		log.info("[Mail] sendMail ip:{} packet:{}", session.ip(), packet.toString());
		Player player = (Player) session.client();
		if (player == null) {
			ErrorHandler.sendErrorMessage(session, ErrorHandler.ERROR_CODE_1, 0);
			log.info("[Mail] sendMail:error player is null ip:{}", session.ip());
			return;
		}
		int num = player.getPool().getInt(PROPERTY_MAILLNUM_DAY, 0);
		if (num >= PLAYER_MAXMAILNUM_DAY) {
			log.info("[Mail] sendMail:error sendMail too much!  ip:{},playerId:{}", session.ip(), player.getInstanceId());
			ErrorHandler.sendErrorMessage(session, ErrorHandler.ERROR_CODE_44, packet.getopcode());
			return;
		}
		int destId = packet.getInt("destId");
		String content = packet.getString("content");
		int useType = MAIL_USERTYPE_OTHER;
		if (packet.containsKey("useType")) {
			useType = packet.getInt("useType");
		}

		Packet pt = new JSONPacket(HOpCode.MAIL_SEND_SERVER);
		if (sendMail(destId, content, player, useType)) {
			log.info("[Mail] sendMail:success ip:{},destId:{},playerId:{},useType:{},content:{}", new Object[] { session.ip(), destId,
					player.getInstanceId(), useType, content });
			packet.getRunTimeMonitor().knock("player send Mail");
			pt.put("result", 1);
			player.getPool().setInt(PROPERTY_MAILLNUM_DAY, ++num);
			packet.getRunTimeMonitor().knock("reset player send mail num");
			// player.notifySave();
		} else {
			pt.put("result", 1);
			log.info("[Mail] sendMail:failed ip:{},destId:{},playerId:{},useType:{},content:{}", new Object[] { session.ip(), destId,
					useType, content });
			ErrorHandler.sendErrorMessage(session, ErrorHandler.ERROR_CODE_5, packet.getopcode());
		}
		session.send(pt);
		log.info("[Mail] playerSendMail():end ip:{}, playerId:{},destId:{},dayMailNum:{}", new Object[] { session.ip(), player.getId(),
				destId, player.getPool().getInt(PROPERTY_MAILLNUM_DAY, 0) });
	}

	@OP(code = 200)
	public void testGmNoticeMail(Packet packet, HSession session) {
		String content = packet.getString("content");
		this.sendSystemNoticeMail(content.split(","));
	}
	@OP(code = 201)
	public void testdelGmNoticeMail(Packet packet, HSession session) {
		this.delSystemNoticeMail("");
	}
	

	@OP(code = HOpCode.MAIL_LIST_CLIENT)
	public void playerListMail(Packet packet, HSession session) {
		log.info("[Mail] playerListMail ip:{} packet:{}", session.ip(), packet.toString());
		Player player = (Player) session.client();
		if (player == null) {
			ErrorHandler.sendErrorMessage(session, ErrorHandler.ERROR_CODE_1, 0);
			log.info("[Mail] playerListMail:error player is null ip:{}", session.ip());
			return;
		}
		int type = packet.getInt("type");
		Packet pt = new JSONPacket(HOpCode.MAIL_LIST_SERVER);
		List<Mail> mails = MailDAO.getPlayerMailByType(player, type);
		packet.getRunTimeMonitor().knock("find player mail from DB type: " + type);
		JSONArray ja = new JSONArray();
		if (type == MAIL_TYPE_SYSTEM) {
			List<Mail> systemNoticeMails = getSystemNotice(player);
			Collections.sort(systemNoticeMails, new Comparator<Mail>(){
				@Override
				public int compare(Mail paramT1, Mail paramT2) {
					return paramT1.getPostTime().compareTo(paramT2.getPostTime());
				}
				
			});
			if (systemNoticeMails != null && !systemNoticeMails.isEmpty()) {
				for (int i = systemNoticeMails.size() - 1; i >= 0; i--) {
					Mail mail = systemNoticeMails.get(i);
					JSONObject jo = new JSONObject();
					jo.put("id", mail.getId());
					jo.put("useType", mail.getUseType());
					jo.put("sourceId", mail.getSourceId());
					jo.put("sourceName", mail.getSourceName());
					jo.put("content", mail.getContent());
					jo.put("sourceIcon", mail.getSourceIcon());
					jo.put("sourceLevel", mail.getSourceLevel());
					jo.put("time", Utils.getDateString(mail.getPostTime()));
					ja.add(jo);
				}
			}
		}
		if (mails != null && mails.size() > 0) {
			for (Mail mail : mails) {
				JSONObject jo = new JSONObject();
				jo.put("id", mail.getId());
				jo.put("useType", mail.getUseType());
				jo.put("sourceId", mail.getSourceId());
				jo.put("sourceName", mail.getSourceName());
				jo.put("content", mail.getContent());
				jo.put("sourceIcon", mail.getSourceIcon());
				jo.put("sourceLevel", mail.getSourceLevel());
				jo.put("time", Utils.getDateString(mail.getPostTime()));
				ja.add(jo);
			}
		}
		if(ja.isEmpty()) {
			pt.put("mailList", "[]");
		} else {
			pt.put("mailList", ja.toString());
		}
		session.send(pt);
		packet.getRunTimeMonitor().knock("mailList to JSON");
		if (mails != null && mails.size() > 0) {
			for (Mail mail : mails) {
				if (mail.getId() > 0 && mail.getStatus() == MAIL_STATE_NOTREAD) {
					mail.setStatus(MAIL_STATE_ALREADYREAD);
					Platform.getEntityManager().updateSync(mail);
				}
			}
		}
		packet.getRunTimeMonitor().knock("set mail already read");
		log.info("[Mail] playerListMail() end,ip:{},playerId:{}, type:{},mailSize:{}", new Object[] { session.ip(), player.getId(), type,
				mails == null ? "0" : mails.size() });
	}

	@OP(code = HOpCode.MAIL_DEL_CLIENT)
	public void playerDelMail(Packet packet, HSession session) {
		log.info("[Mail] playerDelMail ip:{} packet:{}", session.ip(), packet.toString());
		Player player = (Player) session.client();
		if (player == null) {
			ErrorHandler.sendErrorMessage(session, ErrorHandler.ERROR_CODE_1, 0);
			log.info("[Mail] playerDelMail error, player is null ip:{}", session.ip());
			return;
		}
		JSONArray ja = JSONArray.fromObject(packet.getString("mailIds"));
		log.info("[Mail] playerDelMail:{}", ja.toString());
		for (int i = 0; i < ja.size(); i++) {
			JSONObject jo = (JSONObject) ja.get(i);
			int mailId = jo.getInt("mailId");
			if (mailId < 0) {// 系统公告邮件
				player.setNoticeReaded(mailId);
			} else {
				MailDAO.deleteMail(mailId);
				log.info("[Mail] playerDelMail , playerId:{},mailId:{}", player.getId(), mailId);
			}
		}
		packet.getRunTimeMonitor().knock("delete Mails from DB");
		Packet pt = new JSONPacket(HOpCode.MAIL_DEL_SERVER);
		pt.put("result", 1);
		session.send(pt);
		log.info("[Mail] playerDelMail:end, playerId:{},delMailNum:{}", player.getId(), ja.size());
	}

	// ========================================

	public String getSystemWord(int language) {
		if (language == 1) {
			return "系统";
		} else if (language == 2) {
			return "system";
		} else {
			return "system";
		}
	}

	/**
	 * 根据参数返回邮件内容
	 */
	@Override
	public String getMailContent(int language, String sourceName, String destName, int mailTemplateId) {
		MailTemplate mt = mailTemplates.get(mailTemplateId);
		if (mt == null) {
			return "";
		}
		String content = "";
		String str = "";

		switch (language) {
		case 1:
			str = mt.getLanguage1();
			break;
		case 2:
			str = mt.getLanguage2();
			break;
		case 3:
			str = mt.getLanguage3();
			break;
		case 4:
			str = mt.getLanguage4();
			break;
		case 5:
			str = mt.getLanguage5();
			break;
		case 6:
			str = mt.getLanguage6();
			break;
		case 7:
			str = mt.getLanguage7();
			break;
		case 8:
			str = mt.getLanguage8();
			break;
		case 9:
			str = mt.getLanguage9();
			break;
		case 10:
			str = mt.getLanguage10();
			break;
		case 11:
			str = mt.getLanguage11();
			break;
		case 12:
			str = mt.getLanguage12();
			break;
		case 13:
			str = mt.getLanguage13();
			break;
		default:
			str = mt.getDefaultLanguage();
			break;
		}
		if (str == null && "".equals(str)) {
			return "";
		}
		if (sourceName == null) {
			sourceName = "";
		}
		if (destName == null) {
			destName = "";
		}
		content = str.replaceAll("\\$\\(source\\)", sourceName).replaceAll("\\$\\(target\\)", destName);
		log.info("[mail content]  {} ", content);
		return content;
	}

	public void updateMail(Mail mail) throws MailException {

	}

	public List<Mail> list(int playerId, int begin, int count, Date validTime) {
		return null;
	}

	private static final String SYSTEM_NOTIC_SPLITER = "@@@@";

	/**
	 * gm会调用到该方法， 给所有服务器发系统邮件公告
	 */
	@Override
	public boolean sendSystemNoticeMail(String[] content) {
		if (content == null || content.length != this.systemNotice.length) {
			log.error("system notice mail is error: content:{}", Arrays.toString(content));
			return false;
		} else {
			log.info("try send system notice mail: {}", Arrays.toString(content));
		}
		Data data = Platform.dataCenter().getData(DataKeys.systemNotices());
		if(data != null) {
			systemNotice = (TIntObjectMap<Mail>[]) data.value;
		}
		
		int noticeId = -1;// noticeId 设定为负数递减，与正常邮件区分, 有效id从-1 开始。 0为已经删除了的公告
		if (systemNotice[0] != null) {
			noticeId = noticeId - systemNotice[0].size();
		} 
		for (int i = 0; i < content.length; i++) {
			Mail mail = new Mail();
			mail.setId(noticeId);// 为了跟真的邮件区别开， 系统邮件用负数做id；
			mail.setContent(content[i]);
			mail.setPostTime(new Date());
			mail.setSourceId(-1);
			mail.setType(MAIL_TYPE_SYSTEM);
			mail.setSourceName("system");
			mail.setUseType(6);// AK: MailUseType.MAIL_GM
			if (systemNotice[i] == null) {
				systemNotice[i] = new TIntObjectHashMap<Mail>();
			}
			systemNotice[i].put(noticeId, mail);
		}

		boolean succes = false;
		if(data == null) {
			succes = Platform.dataCenter().sendNewData(DataKeys.systemNotices(), systemNotice);
		}else {
			data.value = systemNotice;
			succes = Platform.dataCenter().sendData(DataKeys.systemNotices(), data);
		}
		for (Player player : ObjectAccessor.players.values()) {
			player.getPool().setInt(PlayerService.PLAYER_NOTIFY_CLIENT, PlayerService.NOTIFY_CLIENT);
		}
		return succes;
	}

	@Override
	public int[] getServerLangInfo() {
		return SERVER_LANG_INFO;
	}

	/**
	 * [list, list1, list2,...,list12] [中文， 英文，语言2,..., 语言12]
	 */
	@SuppressWarnings("unchecked")
	private static TIntObjectMap<Mail>[] systemNotice = new TIntObjectMap[13];// 13种语言

	private void updateSystemNotice() {
		Data data = Platform.dataCenter().getData(DataKeys.systemNotices());
		if (data == null) {
			return;
		} else {
			if (data.value == null) {
				return;
			} else {
				systemNotice = (TIntObjectMap<Mail>[]) data.value;
			}
		}
	}

	/**
	 * 获取当前玩家还可以阅读的系统邮件
	 * 
	 * @param player
	 * @return
	 */
	protected List<Mail> getSystemNotice(Player player) {
		final List<Mail> notReadedNotice = new ArrayList<Mail>();
		final int[] hadNotices = player.getReadedNoticeIds();
		if (player.getLang() != 0) {//注意， 小动物的语言是从1开始的， 如果别的语言从0开始， 这里需要重新继承
			if (systemNotice[player.getLang() - 1] != null) {
				systemNotice[player.getLang() - 1].forEachEntry(new TIntObjectProcedure<Mail>() {
					@Override
					public boolean execute(int id, Mail mail) {
						for (int readedNotice : hadNotices) {
							if (readedNotice == id) {
								return true;
							}
						}
						notReadedNotice.add(mail);
						return true;
					}
				});
			}
		}
		return notReadedNotice;

	}

	public boolean hadNoReadSystemNotice(Player player) {
		final int[] hadNotices = player.getReadedNoticeIds();
		if (player.getLang() != 0) {//注意， 小动物的语言是从1开始的， 如果别的语言从0开始， 这里需要重新继承
			if (systemNotice[player.getLang() - 1] != null) {
				return !systemNotice[player.getLang() - 1].forEachKey(new TIntProcedure() {
					@Override
					public boolean execute(int mailId) {
						for (int readedNotice : hadNotices) {
							if (readedNotice == mailId) {
								return true;
							}
						}
						return false;
					}
				});
			}
		}
		return false;
	}
	
	/**
	 * name 现在还没有设计， 该方法是全部删除所有的公告
	 */
	@Override
	public boolean delSystemNoticeMail(String name) {
		Data data = Platform.dataCenter().getData(DataKeys.systemNotices());
		if (data == null) {
			return false;
		}

		data.value =  new TIntObjectMap[13];;
		return Platform.dataCenter().sendData(DataKeys.systemNotices(), data);
	}

	

}
