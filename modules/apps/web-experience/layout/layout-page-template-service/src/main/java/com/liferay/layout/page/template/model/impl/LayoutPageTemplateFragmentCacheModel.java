/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.layout.page.template.model.impl;

import aQute.bnd.annotation.ProviderType;

import com.liferay.layout.page.template.model.LayoutPageTemplateFragment;
import com.liferay.layout.page.template.service.persistence.LayoutPageTemplateFragmentPK;

import com.liferay.portal.kernel.model.CacheModel;
import com.liferay.portal.kernel.util.HashUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import java.util.Date;

/**
 * The cache model class for representing LayoutPageTemplateFragment in entity cache.
 *
 * @author Brian Wing Shun Chan
 * @see LayoutPageTemplateFragment
 * @generated
 */
@ProviderType
public class LayoutPageTemplateFragmentCacheModel implements CacheModel<LayoutPageTemplateFragment>,
	Externalizable {
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (!(obj instanceof LayoutPageTemplateFragmentCacheModel)) {
			return false;
		}

		LayoutPageTemplateFragmentCacheModel layoutPageTemplateFragmentCacheModel =
			(LayoutPageTemplateFragmentCacheModel)obj;

		if (layoutPageTemplateFragmentPK.equals(
					layoutPageTemplateFragmentCacheModel.layoutPageTemplateFragmentPK)) {
			return true;
		}

		return false;
	}

	@Override
	public int hashCode() {
		return HashUtil.hash(0, layoutPageTemplateFragmentPK);
	}

	@Override
	public String toString() {
		StringBundler sb = new StringBundler(19);

		sb.append("{groupId=");
		sb.append(groupId);
		sb.append(", layoutPageTemplateEntryId=");
		sb.append(layoutPageTemplateEntryId);
		sb.append(", fragmentEntryId=");
		sb.append(fragmentEntryId);
		sb.append(", companyId=");
		sb.append(companyId);
		sb.append(", userId=");
		sb.append(userId);
		sb.append(", userName=");
		sb.append(userName);
		sb.append(", createDate=");
		sb.append(createDate);
		sb.append(", modifiedDate=");
		sb.append(modifiedDate);
		sb.append(", position=");
		sb.append(position);
		sb.append("}");

		return sb.toString();
	}

	@Override
	public LayoutPageTemplateFragment toEntityModel() {
		LayoutPageTemplateFragmentImpl layoutPageTemplateFragmentImpl = new LayoutPageTemplateFragmentImpl();

		layoutPageTemplateFragmentImpl.setGroupId(groupId);
		layoutPageTemplateFragmentImpl.setLayoutPageTemplateEntryId(layoutPageTemplateEntryId);
		layoutPageTemplateFragmentImpl.setFragmentEntryId(fragmentEntryId);
		layoutPageTemplateFragmentImpl.setCompanyId(companyId);
		layoutPageTemplateFragmentImpl.setUserId(userId);

		if (userName == null) {
			layoutPageTemplateFragmentImpl.setUserName(StringPool.BLANK);
		}
		else {
			layoutPageTemplateFragmentImpl.setUserName(userName);
		}

		if (createDate == Long.MIN_VALUE) {
			layoutPageTemplateFragmentImpl.setCreateDate(null);
		}
		else {
			layoutPageTemplateFragmentImpl.setCreateDate(new Date(createDate));
		}

		if (modifiedDate == Long.MIN_VALUE) {
			layoutPageTemplateFragmentImpl.setModifiedDate(null);
		}
		else {
			layoutPageTemplateFragmentImpl.setModifiedDate(new Date(
					modifiedDate));
		}

		layoutPageTemplateFragmentImpl.setPosition(position);

		layoutPageTemplateFragmentImpl.resetOriginalValues();

		return layoutPageTemplateFragmentImpl;
	}

	@Override
	public void readExternal(ObjectInput objectInput) throws IOException {
		groupId = objectInput.readLong();

		layoutPageTemplateEntryId = objectInput.readLong();

		fragmentEntryId = objectInput.readLong();

		companyId = objectInput.readLong();

		userId = objectInput.readLong();
		userName = objectInput.readUTF();
		createDate = objectInput.readLong();
		modifiedDate = objectInput.readLong();

		position = objectInput.readInt();

		layoutPageTemplateFragmentPK = new LayoutPageTemplateFragmentPK(groupId,
				layoutPageTemplateEntryId, fragmentEntryId);
	}

	@Override
	public void writeExternal(ObjectOutput objectOutput)
		throws IOException {
		objectOutput.writeLong(groupId);

		objectOutput.writeLong(layoutPageTemplateEntryId);

		objectOutput.writeLong(fragmentEntryId);

		objectOutput.writeLong(companyId);

		objectOutput.writeLong(userId);

		if (userName == null) {
			objectOutput.writeUTF(StringPool.BLANK);
		}
		else {
			objectOutput.writeUTF(userName);
		}

		objectOutput.writeLong(createDate);
		objectOutput.writeLong(modifiedDate);

		objectOutput.writeInt(position);
	}

	public long groupId;
	public long layoutPageTemplateEntryId;
	public long fragmentEntryId;
	public long companyId;
	public long userId;
	public String userName;
	public long createDate;
	public long modifiedDate;
	public int position;
	public transient LayoutPageTemplateFragmentPK layoutPageTemplateFragmentPK;
}