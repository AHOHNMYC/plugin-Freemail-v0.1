package freemail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.math.BigInteger;
import java.security.SecureRandom;

import freemail.utils.EmailAddress;
import freemail.utils.PropsFile;
import freemail.utils.DateStringFactory;
import freemail.utils.ChainedAsymmetricBlockCipher;
import freemail.fcp.HighLevelFCPClient;
import freemail.fcp.FCPInsertErrorMessage;
import freemail.fcp.FCPBadFileException;
import freemail.fcp.SSKKeyPair;

import org.archive.util.Base32;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.engines.RSAEngine;
import org.bouncycastle.crypto.InvalidCipherTextException;

public class OutboundContact {
	public static final String OUTBOX_DIR = "outbox";
	private final PropsFile contactfile;
	private final File accdir;
	private final File ctoutbox;
	private final EmailAddress address;
	// how long to wait for a CTS before sending the message again
	// slightly over 24 hours since some people are likley to fire Freemail
	// up and roughly the same time every day
	private static final long CTS_WAIT_TIME = 26 * 60 * 60 * 1000;
	private static final String PROPSFILE_NAME = "props";
	// how long do we wait before retransmitting the message? 26 hours allows for people starting Freemail at roughly the same time every day
	private static final long RETRANSMIT_DELAY = 26 * 60 * 60 * 1000;
	// how long do we wait before we give up all hope and just bounce the message back? 5 days is fairly standard, so we'll go with that for now, except that means things bounce when the recipient goes to the Bahamas for a fortnight. Could be longer if we have a GUI to see what messages are in what delivery state.
	private static final long FAIL_DELAY = 5 * 24 * 60 * 60 * 1000;
	
	public OutboundContact(File accdir, EmailAddress a) throws BadFreemailAddressException {
		this.address = a;
		
		this.accdir = accdir;
		
		if (this.address.getMailsiteKey() == null) {
			this.contactfile = null;
			throw new BadFreemailAddressException();
		} else {
			File contactsdir = new File(accdir, SingleAccountWatcher.CONTACTS_DIR);
			if (!contactsdir.exists())
				contactsdir.mkdir();
			File outbounddir = new File(contactsdir, SingleAccountWatcher.OUTBOUND_DIR);
			
			if (!outbounddir.exists())
				outbounddir.mkdir();
			
			File obctdir = new File(outbounddir, this.address.getMailsiteKey());
			
			if (!obctdir.exists())
				obctdir.mkdir();
			
			this.contactfile = new PropsFile(new File(obctdir, PROPSFILE_NAME));
			this.ctoutbox = new File(obctdir, OUTBOX_DIR);
			if (!this.ctoutbox.exists())
				this.ctoutbox.mkdir();
		}
	}
	
	public OutboundContact(File accdir, File ctdir) {
		this.accdir = accdir;
		this.address = new EmailAddress();
		this.address.domain = Base32.encode(ctdir.getName().getBytes())+".freemail";
		
		this.contactfile = new PropsFile(new File(ctdir, PROPSFILE_NAME));
		
		this.ctoutbox = new File(ctdir, OUTBOX_DIR);
		if (!this.ctoutbox.exists())
			this.ctoutbox.mkdir();
	}
	
	public void checkCTS() throws OutboundContactFatalException {
		String status = this.contactfile.get("status");
		if (status == null) {
			this.init();
		}
		
		if (status.equals("cts-received")) {
			return;
		} else if (status.equals("rts-sent")) {
			// poll for the CTS message
			
			String ctskey = this.contactfile.get("ackssk.pubkey");
			if (ctskey == null) {
				this.init();
			}
			ctskey += "ack";
			
			HighLevelFCPClient fcpcli = new HighLevelFCPClient();
			
			File cts = fcpcli.fetch(ctskey);
			
			if (cts == null) {
				// haven't got the CTS message. should we give up yet?
				String senttime = this.contactfile.get("rts-sent-at");
				
				if (senttime == null || Long.parseLong(senttime) > System.currentTimeMillis() + CTS_WAIT_TIME) {
					// yes, send another RTS
					this.init();
				}
				
			} else {
				System.out.println("Sucessfully received CTS for "+this.address.getMailsiteKey());
				cts.delete();
				this.contactfile.put("status", "cts-received");
				// delete inital slot for forward secrecy
				this.contactfile.remove("initialslot");
			}
		} else {
			this.init();
		}
	}
	
	/*
	 * Whether or not we're ready to communicate with the other party
	 */
	public boolean ready() {
		if (!this.contactfile.exists()) return false;
		
		String status = this.contactfile.get("status");
		if (status == null) return false;
		// don't wait for an ack before inserting the message, but be ready to insert it again
		// if the ack never arrives
		if (status.equals("rts-sent")) return true;
		if (status.equals("cts-received")) return true;
		return false;
	}
	
	private SSKKeyPair getCommKeyPair() {
		SSKKeyPair ssk = new SSKKeyPair();
		
		ssk.pubkey = this.contactfile.get("commssk.pubkey");
		ssk.privkey = this.contactfile.get("commssk.privkey");
		
		
		if (ssk.pubkey == null || ssk.privkey == null) {
			HighLevelFCPClient cli = new HighLevelFCPClient();
			ssk = cli.makeSSK();
			
			this.contactfile.put("commssk.privkey", ssk.privkey);
			this.contactfile.put("commssk.pubkey", ssk.pubkey);
			// we've just generated a new SSK, so the other party definately doesn't know about it
			this.contactfile.put("status", "notsent");
		}
		
		return ssk;
	}
	
	private SSKKeyPair getAckKeyPair() {
		SSKKeyPair ssk = new SSKKeyPair();
		
		ssk.pubkey = this.contactfile.get("ackssk.pubkey");
		ssk.privkey = this.contactfile.get("ackssk.privkey");
		
		
		if (ssk.pubkey == null || ssk.privkey == null) {
			HighLevelFCPClient cli = new HighLevelFCPClient();
			ssk = cli.makeSSK();
			
			this.contactfile.put("ackssk.privkey", ssk.privkey);
			this.contactfile.put("ackssk.pubkey", ssk.pubkey);
		}
		
		return ssk;
	}
	
	private RSAKeyParameters getPubKey() throws OutboundContactFatalException {
		String mod_str = this.contactfile.get("asymkey.modulus");
		String exp_str = this.contactfile.get("asymkey.pubexponent");
		
		if (mod_str == null || exp_str == null) {
			// we don't have their mailsite - fetch it
			if (this.fetchMailSite()) {
				mod_str = this.contactfile.get("asymkey.modulus");
				exp_str = this.contactfile.get("asymkey.pubexponent");
				
				// must be present now, or exception would have been thrown
			} else {
				return null;
			}
		}
		
		return new RSAKeyParameters(false, new BigInteger(mod_str, 10), new BigInteger(exp_str, 10));
	}
	
	private String getRtsKsk() throws OutboundContactFatalException {
		String rtsksk = this.contactfile.get("rtsksk");
		
		if (rtsksk == null) {
			// get it from their mailsite
			if (!this.fetchMailSite()) return null;
			
			rtsksk = this.contactfile.get("rtsksk");
		}
		
		return rtsksk;
	}
	
	private String getInitialSlot() {
		String retval = this.contactfile.get("initialslot");
		
		if (retval != null) return retval;
		
		SecureRandom rnd = new SecureRandom();
		SHA256Digest sha256 = new SHA256Digest();
		byte[] buf = new byte[sha256.getDigestSize()];
		
		rnd.nextBytes(buf);
		
		this.contactfile.put("initialslot", Base32.encode(buf));
		
		return Base32.encode(buf);
	}
	
	/**
	 * Set up an outbound contact. Fetch the mailsite, generate a new SSK keypair and post an RTS message to the appropriate KSK.
	 * Will block for mailsite retrieval and RTS insertion
	 *
	 * @return true for success
	 */
	public boolean init() throws OutboundContactFatalException {
		// try to fetch get all necessary info. will fetch mailsite / generate new keys if necessary
		SSKKeyPair commssk = this.getCommKeyPair();
		if (commssk == null) return false;
		SSKKeyPair ackssk = this.getAckKeyPair();
		RSAKeyParameters their_pub_key = this.getPubKey();
		if (their_pub_key == null) return false;
		String rtsksk = this.getRtsKsk();
		if (rtsksk == null) return false;
		
		StringBuffer rtsmessage = new StringBuffer();
		
		// the public part of the SSK keypair we generated
		// put this first to avoid messages with the same first block, since we don't (currently) use CBC
		rtsmessage.append("commssk="+commssk.pubkey+"\r\n");
		
		rtsmessage.append("ackssk="+ackssk.privkey+"\r\n");
		
		String initialslot = this.getInitialSlot();
		
		rtsmessage.append("initialslot="+initialslot+"\r\n");
		
		rtsmessage.append("messagetype=rts\r\n");
		
		// must include who this RTS is to, otherwise we're vulnerable to surruptitious forwarding
		rtsmessage.append("to="+this.address.getMailsiteKey()+"\r\n");
		
		// get our mailsite URI
		String our_mailsite_uri = AccountManager.getAccountFile(this.accdir).get("mailsite.pubkey");
		
		rtsmessage.append("mailsite="+our_mailsite_uri+"\r\n");
		
		rtsmessage.append("\r\n");
		System.out.println(rtsmessage.toString());
		
		// sign the message
		
		SHA256Digest sha256 = new SHA256Digest();
		sha256.update(rtsmessage.toString().getBytes(), 0, rtsmessage.toString().getBytes().length);
		byte[] hash = new byte[sha256.getDigestSize()];
		sha256.doFinal(hash, 0);
		
		RSAKeyParameters our_priv_key = AccountManager.getPrivateKey(this.accdir);
		
		AsymmetricBlockCipher sigcipher = new RSAEngine();
		sigcipher.init(true, our_priv_key);
		byte[] sig = null;
		try {
			sig = sigcipher.processBlock(hash, 0, hash.length);
		} catch (InvalidCipherTextException e) {
			e.printStackTrace();
			return false;
		}
		
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		
		try {
			bos.write(rtsmessage.toString().getBytes());
			bos.write(sig);
		} catch(IOException ioe) {
			ioe.printStackTrace();
			return false;
		}
		
		// now encrypt it
		AsymmetricBlockCipher enccipher = new RSAEngine();
		enccipher.init(true, their_pub_key);
		byte[] encmsg = null;
		try {
			encmsg = ChainedAsymmetricBlockCipher.encrypt(enccipher, bos.toByteArray());
		} catch (InvalidCipherTextException e) {
			e.printStackTrace();
			return false;
		}
		
		// insert it!
		HighLevelFCPClient cli = new HighLevelFCPClient();
		if (cli.SlotInsert(encmsg, "KSK@"+rtsksk+"-"+DateStringFactory.getKeyString(), 1, "") < 0) {
			return false;
		}
		
		// remember the fact that we have successfully inserted the rts
		this.contactfile.put("status", "rts-sent");
		// and remember when we sent it!
		this.contactfile.put("rts-sent-at", Long.toString(System.currentTimeMillis()));
		
		return true;
	}
	
	private boolean fetchMailSite() throws OutboundContactFatalException {
		HighLevelFCPClient cli = new HighLevelFCPClient();
		
		System.out.println("Attempting to fetch "+this.getMailpageKey());
		File mailsite_file = cli.fetch(this.getMailpageKey());
		
		if (mailsite_file == null) {
			// TODO: Give up for now, try later, count number of and limit attempts
			System.out.println("Failed to retrieve mailsite for "+this.address);
			return false;
		}
		
		System.out.println("got mailsite");
		
		PropsFile mailsite = new PropsFile(mailsite_file);
		
		String rtsksk = mailsite.get("rtsksk");
		String keymod_str = mailsite.get("asymkey.modulus");
		String keyexp_str = mailsite.get("asymkey.pubexponent");
		
		mailsite_file.delete();
		
		if (rtsksk == null || keymod_str == null || keyexp_str == null) {
			// TODO: More failure mechanisms - this is fatal.
			System.out.println("Mailsite for "+this.address+" does not contain all necessary iformation!");
			throw new OutboundContactFatalException("Mailsite for "+this.address+" does not contain all necessary iformation!");
		}
		
		// add this to a new outbound contact file
		this.contactfile.put("rtsksk", rtsksk);
		this.contactfile.put("asymkey.modulus", keymod_str);
		this.contactfile.put("asymkey.pubexponent", keyexp_str);
		
		return true;
	}
	
	private String getMailpageKey() {
		return "USK@"+this.address.getMailsiteKey()+"/"+AccountManager.MAILSITE_SUFFIX+"/1/"+MailSite.MAILPAGE;
	}
	
	private String popNextSlot() {
		String slot = this.contactfile.get("nextslot");
		if (slot == null) {
			slot = this.contactfile.get("initialslot");
		}
		SHA256Digest sha256 = new SHA256Digest();
		sha256.update(Base32.decode(slot), 0, Base32.decode(slot).length);
		byte[] nextslot = new byte[sha256.getDigestSize()];
		sha256.doFinal(nextslot, 0);
		this.contactfile.put("nextslot", Base32.encode(nextslot));
		
		return slot;
	}
	
	private int popNextUid() {
		String nextuid_s = this.contactfile.get("nextuid");
		
		int nextuid;
		if (nextuid_s == null)
			nextuid = 1;
		else
			nextuid = Integer.parseInt(nextuid_s);
		
		this.contactfile.put("nextuid", Integer.toString(nextuid + 1));
		return nextuid;
	}
	
	public boolean sendMessage(File body) {
		int uid = this.popNextUid();
		
		// create a new file that contains the complete Freemail
		// message, with Freemail headers
		QueuedMessage qm = new QueuedMessage(uid);
		
		File msg;
		FileOutputStream fos;
		try {
			msg = File.createTempFile("ogm", "msg", Freemail.getTempDir());
			
			fos = new FileOutputStream(msg);
		} catch (IOException ioe) {
			System.out.println("IO Error encountered whilst trying to send message: "+ioe.getMessage()+" Will try again soon");
			return false;
		}
		
		FileInputStream fis;
		try {
			fos.write(new String("id="+uid+"\r\n\r\n").getBytes());
		
			fis = new FileInputStream(body);
			
			byte[] buf = new byte[1024];
			int read;
			while ( (read = fis.read(buf)) > 0) {
				fos.write(buf, 0, read);
			}
			
			fos.close();
		} catch (IOException ioe) {
			System.out.println("IO Error encountered whilst trying to send message: "+ioe.getMessage()+" Will try again soon");
			qm.delete();
			msg.delete();
			return false;
		}
		
		String slot = this.popNextSlot();
		
		qm.slot = slot;
		
		if (qm.setMessageFile(msg) && qm.saveProps()) {
			body.delete();
			return true;
		}
		return false;
	}
	
	public void doComm() {
		this.sendQueued();
		this.pollAcks();
	}
	
	private void sendQueued() {
		HighLevelFCPClient fcpcli = null;
		
		QueuedMessage[] msgs = this.getSendQueue();
		
		int i;
		for (i = 0; i < msgs.length; i++) {
			if (msgs[i] == null) continue;
			if (msgs[i].last_send_time > 0) continue;
			
			if (fcpcli == null) fcpcli = new HighLevelFCPClient();
			
			String key = this.contactfile.get("commssk.privkey");
			
			if (key == null) {
				System.out.println("Contact file does not contain private communication key! It appears that your Freemail directory is corrupt!");
				continue;
			}
			
			key += msgs[i].slot;
			
			FileInputStream fis;
			try {
				fis = new FileInputStream(msgs[i].file);
			} catch (FileNotFoundException fnfe) {
				continue;
			}
			
			System.out.println("Inserting message to "+key);
			FCPInsertErrorMessage err;
			try {
				err = fcpcli.put(fis, key);
			} catch (FCPBadFileException bfe) {
				System.out.println("Failed sending message. Will try again soon.");
				continue;
			}
			if (err == null) {
				System.out.println("Successfully inserted "+key);
				msgs[i].first_send_time = System.currentTimeMillis();
				msgs[i].last_send_time = System.currentTimeMillis();
				msgs[i].saveProps();
			} else {
				System.out.println("Failed to insert "+key+" will try again soon.");
			}
		}
	}
	
	private void pollAcks() {
		HighLevelFCPClient fcpcli = null;
		QueuedMessage[] msgs = this.getSendQueue();
		
		int i;
		for (i = 0; i < msgs.length; i++) {
			if (msgs[i] == null) continue;
			if (msgs[i].first_send_time < 0) continue;
			
			if (fcpcli == null) fcpcli = new HighLevelFCPClient();
			
			String key = this.contactfile.get("ackssk.pubkey");
			if (key == null) {
				System.out.println("Contact file does not contain public ack key! It appears that your Freemail directory is corrupt!");
				continue;
			}
			
			key += msgs[i].uid;
			
			File ack = fcpcli.fetch(key);
			if (ack != null) {
				System.out.println("Ack received for message "+msgs[i].uid+" on contact "+this.address.domain+". Now that's a job well done.");
				ack.delete();
				msgs[i].delete();
				// treat the ACK as a CTS too
				this.contactfile.put("status", "cts-received");
				// delete inital slot for forward secrecy
				this.contactfile.remove("initialslot");
			} else {
				if (System.currentTimeMillis() > msgs[i].first_send_time + FAIL_DELAY) {
					// give up and bounce the message
					// TODO: bounce message
					System.out.println("Giving up on message - been trying for too long.");
					msgs[i].delete();
				} else if (System.currentTimeMillis() > msgs[i].last_send_time + RETRANSMIT_DELAY) {
					// no ack yet - retransmit on another slot
					msgs[i].slot = this.popNextSlot();
					// mark for re-insertion
					msgs[i].last_send_time = -1;
					msgs[i].saveProps();
				}
			}
		}
	}
	
	private QueuedMessage[] getSendQueue() {
		File[] files = ctoutbox.listFiles();
		QueuedMessage[] msgs = new QueuedMessage[files.length];
		
		int i;
		for (i = 0; i < files.length; i++) {
			if (files[i].getName().equals(QueuedMessage.INDEX_FILE)) continue;
				
			int uid;
			try {
				uid = Integer.parseInt(files[i].getName());
			} catch (NumberFormatException nfe) {
				// how did that get there? just delete it
				System.out.println("Found spurious file in send queue - deleting.");
				files[i].delete();
				msgs[i] = null;
				continue;
			}
			
			msgs[i] = new QueuedMessage(uid);
		}
		
		return msgs;
	}
	
	private class QueuedMessage {
		static final String INDEX_FILE = "_index";
	
		PropsFile index;
		
		final int uid;
		String slot;
		long first_send_time;
		long last_send_time;
		private final File file;
		
		public QueuedMessage(int uid) {
			this.uid = uid;
			this.file = new File(ctoutbox, Integer.toString(uid));
			
			this.index = new PropsFile(new File(ctoutbox, INDEX_FILE));
			
			this.slot = this.index.get(uid+".slot");
			String s_first = this.index.get(uid+".first_send_time");
			if (s_first == null)
				this.first_send_time = -1;
			else
				this.first_send_time = Long.parseLong(s_first);
			
			String s_last = this.index.get(uid+".last_send_time");
			if (s_last == null)
				this.last_send_time = -1;
			else
				this.last_send_time = Long.parseLong(s_last);
			
		}
		
		public FileInputStream getInputStream() throws FileNotFoundException {
			return new FileInputStream(this.file);
		}
		
		public boolean setMessageFile(File newfile) {
			return newfile.renameTo(this.file);
		}
	
		public boolean saveProps() {
			boolean suc = true;
			suc &= this.index.put(uid+".slot", this.slot);
			suc &= this.index.put(uid+".first_send_time", this.first_send_time);
			suc &= this.index.put(uid+".last_send_time", this.last_send_time);
			
			return suc;
		}
		
		public boolean delete() {
			this.index.remove(this.uid+".slot");
			this.index.remove(this.uid+".first_send_time");
			this.index.remove(this.uid+".last_send_time");
			
			return this.file.delete();
		}
	}
}
