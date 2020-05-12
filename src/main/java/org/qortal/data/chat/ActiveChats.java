package org.qortal.data.chat;

import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ActiveChats {

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class GroupChat {
		private int groupId;
		private String groupName;
		private long timestamp;

		protected GroupChat() {
			/* JAXB */
		}

		public GroupChat(int groupId, String groupName, long timestamp) {
			this.groupId = groupId;
			this.groupName = groupName;
			this.timestamp = timestamp;
		}

		public int getGroupId() {
			return this.groupId;
		}

		public String getGroupName() {
			return this.groupName;
		}

		public long getTimestamp() {
			return this.timestamp;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class DirectChat {
		private String address;
		private String name;
		private long timestamp;

		protected DirectChat() {
			/* JAXB */
		}

		public DirectChat(String address, String name, long timestamp) {
			this.address = address;
			this.name = name;
			this.timestamp = timestamp;
		}

		public String getAddress() {
			return this.address;
		}

		public String getName() {
			return this.name;
		}

		public long getTimestamp() {
			return this.timestamp;
		}
	}

	// Properties

	private List<GroupChat> groups;

	private List<DirectChat> direct;

	// Constructors

	protected ActiveChats() {
		/* For JAXB */
	}

	// For repository use
	public ActiveChats(List<GroupChat> groups, List<DirectChat> direct) {
		this.groups = groups;
		this.direct = direct;
	}

	public List<GroupChat> getGroups() {
		return this.groups;
	}

	public List<DirectChat> getDirect() {
		return this.direct;
	}

}
