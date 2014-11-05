package com.gsma.services.rcs.vsh;

import com.gsma.services.rcs.vsh.IVideoPlayer;
import com.gsma.services.rcs.vsh.VideoDescriptor;
import com.gsma.services.rcs.contacts.ContactId;

/**
 * Video sharing interface
 */
interface IVideoSharing {

	String getSharingId();

	ContactId getRemoteContact();

	VideoDescriptor getVideoDescriptor();

	int getState();

	int getReasonCode();

	int getDirection();
	
	void acceptInvitation(IVideoPlayer player);

	void rejectInvitation();

	void abortSharing();
	
	void setOrientation(int orientation);
	
	String getVideoEncoding();

	long getTimeStamp();

	long getDuration();
}
