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

package com.liferay.mobile.device.rules.service.persistence.impl;

import com.liferay.mobile.device.rules.model.MDRRuleGroup;
import com.liferay.mobile.device.rules.service.persistence.MDRRuleGroupPersistence;

import com.liferay.portal.kernel.bean.BeanReference;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.service.persistence.impl.BasePersistenceImpl;

import java.util.Set;

/**
 * @author Edward C. Han
 * @generated
 */
public class MDRRuleGroupFinderBaseImpl extends BasePersistenceImpl<MDRRuleGroup> {
	public MDRRuleGroupFinderBaseImpl() {
		setModelClass(MDRRuleGroup.class);
	}

	@Override
	public Set<String> getBadColumnNames() {
		return getMDRRuleGroupPersistence().getBadColumnNames();
	}

	/**
	 * Returns the mdr rule group persistence.
	 *
	 * @return the mdr rule group persistence
	 */
	public MDRRuleGroupPersistence getMDRRuleGroupPersistence() {
		return mdrRuleGroupPersistence;
	}

	/**
	 * Sets the mdr rule group persistence.
	 *
	 * @param mdrRuleGroupPersistence the mdr rule group persistence
	 */
	public void setMDRRuleGroupPersistence(
		MDRRuleGroupPersistence mdrRuleGroupPersistence) {
		this.mdrRuleGroupPersistence = mdrRuleGroupPersistence;
	}

	@BeanReference(type = MDRRuleGroupPersistence.class)
	protected MDRRuleGroupPersistence mdrRuleGroupPersistence;
	private static final Log _log = LogFactoryUtil.getLog(MDRRuleGroupFinderBaseImpl.class);
}