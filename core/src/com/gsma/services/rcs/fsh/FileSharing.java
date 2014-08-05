/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
 * Copyright (C) 2014 Sony Mobile Communications Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NOTE: This file has been modified by Sony Mobile Communications Inc.
 * Modifications are licensed under the License.
 ******************************************************************************/
package com.gsma.services.rcs.fsh;

import com.gsma.services.rcs.JoynServiceException;
import com.gsma.services.rcs.contacts.ContactId;

import android.net.Uri;

/**
 * File sharing
 * 
 * @author Jean-Marc AUFFRET
 */
public class FileSharing {

    /**
     * File sharing state
     */
    public static class State {
    	/**
    	 * Inactive state
    	 */
    	public final static int INACTIVE = 0;

    	/**
    	 * Sharing invitation received
    	 */
    	public final static int INVITED = 1;
    	
    	/**
    	 * Sharing invitation sent
    	 */
    	public final static int INITIATED = 2;
    	
    	/**
    	 * Sharing is started
    	 */
    	public final static int STARTED = 3;
    	
    	/**
    	 * File link has been transferred with success 
    	 */
    	public final static int TRANSFERRED = 4;
    	
    	/**
    	 * Sharing has been aborted 
    	 */
    	public final static int ABORTED = 5;
    	
    	/**
    	 * Sharing has failed 
    	 */
    	public final static int FAILED = 6;

    	/**
    	 * Call ringing
    	 */
    	public final static int RINGING = 7;

        private State() {
        }    	
    }
    
    /**
     * Direction of the sharing
     */
    public static class Direction {
        /**
         * Incoming sharing
         */
        public static final int INCOMING = 0;
        
        /**
         * Outgoing sharing
         */
        public static final int OUTGOING = 1;
    }      
    
    /**
     * File sharing error
     */
    public static class Error {
    	/**
    	 * Sharing has failed
    	 */
    	public final static int SHARING_FAILED = 0;
    	
    	/**
    	 * Sharing invitation has been declined by remote
    	 */
    	public final static int INVITATION_DECLINED = 1;
    	
        private Error() {
        }    	
    }

    /**
     * File sharing interface
     */
    private IFileSharing sharingInf;
    
    /**
     * Constructor
     * 
     * @param sharingInf Image sharing interface
     */
    FileSharing(IFileSharing sharingInf) {
    	this.sharingInf = sharingInf;
    }
    	
    /**
	 * Returns the sharing ID of the file sharing
	 * 
	 * @return Sharing ID
	 * @throws JoynServiceException
	 */
	public String getSharingId() throws JoynServiceException {
		try {
			return sharingInf.getSharingId();
		} catch(Exception e) {
			throw new JoynServiceException(e.getMessage());
		}
	}
	
	/**
	 * Returns the remote contact identifier
	 * 
	 * @return ContactId
	 * @throws JoynServiceException
	 */
	public ContactId getRemoteContact() throws JoynServiceException {
		try {
			return sharingInf.getRemoteContact();
		} catch(Exception e) {
			throw new JoynServiceException(e.getMessage());
		}
	}

	/**
	 * Returns the URI of the shared file
	 *
	 * @return Uri
	 * @throws JoynServiceException
	 */
	public Uri getFile() throws JoynServiceException {
		try {
			return sharingInf.getFile();
		} catch (Exception e) {
			throw new JoynServiceException(e.getMessage());
		}
	}

	/**
	 * Returns the state of the sharing
	 * 
	 * @return State
	 * @see FileSharing.State
	 * @throws JoynServiceException
	 */
	public int getState() throws JoynServiceException {
		try {
			return sharingInf.getState();
		} catch(Exception e) {
			throw new JoynServiceException(e.getMessage());
		}
	}		
		
	/**
	 * Returns the direction of the sharing (incoming or outgoing)
	 * 
	 * @return Direction
	 * @see FileSharing.Direction
	 * @throws JoynServiceException
	 */
	public int getDirection() throws JoynServiceException {
		try {
			return sharingInf.getDirection();
		} catch(Exception e) {
			throw new JoynServiceException(e.getMessage());
		}
	}	
	
	/**
	 * Accepts file sharing invitation
	 * 
	 * @throws JoynServiceException
	 */
	public void acceptInvitation() throws JoynServiceException {
		try {
			sharingInf.acceptInvitation();
		} catch(Exception e) {
			throw new JoynServiceException(e.getMessage());
		}
	}
	
	/**
	 * Rejects file sharing invitation
	 * 
	 * @throws JoynServiceException
	 */
	public void rejectInvitation() throws JoynServiceException {
		try {
			sharingInf.rejectInvitation();
		} catch(Exception e) {
			throw new JoynServiceException(e.getMessage());
		}
	}

	/**
	 * Aborts the sharing
	 * 
	 * @throws JoynServiceException
	 */
	public void abortSharing() throws JoynServiceException {
		try {
			sharingInf.abortSharing();
		} catch(Exception e) {
			throw new JoynServiceException(e.getMessage());
		}
	}
}