/*
 * Copyright (C) 2015 Sony Mobile Communications Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.gsma.rcs.provider.messaging;

import com.gsma.rcs.core.ims.service.im.InstantMessagingService;
import com.gsma.rcs.core.ims.service.im.chat.ChatMessage;
import com.gsma.rcs.core.ims.service.im.chat.ChatUtils;
import com.gsma.rcs.core.ims.service.im.chat.OneToOneChatSession;
import com.gsma.rcs.provider.contact.ContactManager;
import com.gsma.rcs.provider.settings.RcsSettings;
import com.gsma.rcs.service.DequeueTask;
import com.gsma.rcs.service.api.ChatServiceImpl;
import com.gsma.rcs.service.api.OneToOneChatImpl;
import com.gsma.rcs.utils.ContactUtil;
import com.gsma.services.rcs.contact.ContactId;

import android.database.Cursor;

/**
 * AllOneToOneChatMessageDequeueTask tries to dequeues and sends all queued one-one chat messages.
 */
public class AllOneToOneChatMessageDequeueTask extends DequeueTask {

    private final ChatServiceImpl mChatService;

    public AllOneToOneChatMessageDequeueTask(Object lock, InstantMessagingService imService,
            MessagingLog messagingLog, ChatServiceImpl chatService, RcsSettings rcsSettings,
            ContactManager contactManager) {
        super(lock, imService, contactManager, messagingLog, rcsSettings);
        mChatService = chatService;
    }

    public void run() {
        boolean logActivated = mLogger.isActivated();
        if (logActivated) {
            mLogger.debug("Execute task to dequeue one-to-one chat messages.");
        }
        Cursor cursor = null;
        try {
            synchronized (mLock) {
                cursor = mMessagingLog.getAllQueuedOneToOneChatMessages();
                int msgIdIdx = cursor.getColumnIndexOrThrow(MessageData.KEY_MESSAGE_ID);
                int contentIdx = cursor.getColumnIndexOrThrow(MessageData.KEY_CONTENT);
                int mimeTypeIdx = cursor.getColumnIndexOrThrow(MessageData.KEY_MIME_TYPE);
                int contactIdx = cursor.getColumnIndexOrThrow(MessageData.KEY_CONTACT);
                while (cursor.moveToNext()) {
                    String msgId = cursor.getString(msgIdIdx);
                    String content = cursor.getString(contentIdx);
                    String mimeType = cursor.getString(mimeTypeIdx);
                    long timestamp = System.currentTimeMillis();
                    ContactId contact = ContactUtil.createContactIdFromTrustedData(cursor
                            .getString(contactIdx));
                    /* For outgoing message, timestampSent = timestamp */
                    ChatMessage message = ChatUtils.createChatMessage(msgId, mimeType, content,
                            contact, null, timestamp, timestamp);
                    try {
                        if (isAllowedToDequeueOneToOneChatMessage(contact)) {
                            OneToOneChatImpl oneToOneChat = mChatService
                                    .getOrCreateOneToOneChat(contact);
                            OneToOneChatSession session = mImService
                                    .getOneToOneChatSession(contact);
                            if (session == null) {
                                oneToOneChat.dequeueChatMessageInNewSession(message);
                            } else if (session.isMediaEstablished()) {
                                oneToOneChat.dequeueChatMessageWithinSession(message, session);
                            } else if (session.isInitiatedByRemote()) {
                                session.acceptSession();
                            } else {
                                oneToOneChat.dequeueChatMessageInNewSession(message);
                            }
                        }
                    } catch (Exception e) {
                        /* Exceptions will be handled better in CR037 */
                        /*
                         * Break only for terminal exception, in rest of the cases dequeue and try
                         * to send other messages.
                         */
                        if (logActivated) {
                            mLogger.error(
                                    new StringBuilder(
                                            "Exception occured while dequeueing one-to-one chat message with msgId '")
                                            .append(msgId).append("'for contact '").append(contact)
                                            .append("' ").toString(), e);
                        }
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}