/*
 * Copyright 2002-2004 the original author or authors.
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
 */ 

package org.springframework.ide.eclipse.beans.core.internal.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.springframework.ide.eclipse.beans.core.model.IBean;
import org.springframework.ide.eclipse.beans.core.model.IBeansConfig;
import org.springframework.ide.eclipse.beans.core.model.IBeansConfigSet;
import org.springframework.ide.eclipse.beans.core.model.IBeansModelElementTypes;
import org.springframework.ide.eclipse.beans.core.model.IBeansProject;
import org.springframework.ide.eclipse.core.model.AbstractSourceModelElement;
import org.springframework.ide.eclipse.core.model.IModelElement;
import org.springframework.ide.eclipse.core.model.IResourceModelElement;

/**
 * This class defines a Spring beans config set (a list of beans config names).
 */
public class BeansConfigSet extends AbstractSourceModelElement
												   implements IBeansConfigSet {
	private List configNames;
	private boolean allowBeanDefinitionOverriding;
	private boolean isIncomplete;
	private Map beansMap;
	private Map beanClassesMap;

	public BeansConfigSet(IBeansProject project, String name) {
		this(project, name, new ArrayList());
	}

	public BeansConfigSet(IBeansProject project, String name, List configNames) {
		super(project, name);
		this.allowBeanDefinitionOverriding = true;
		this.configNames = new ArrayList(configNames); 
	}

	/**
	 * Sets internal maps with <code>IBean</code>s and bean classes to
	 * <code>null</code>.
	 */
	public void reset() {
		this.beansMap = null;
		this.beanClassesMap = null;
	}

	public int getElementType() {
		return IBeansModelElementTypes.CONFIG_SET_TYPE;
	}

	public IResource getElementResource() {
		return (getElementParent() instanceof IResourceModelElement ?
					((IResourceModelElement)
							getElementParent()).getElementResource() :
							null);
	}

	public IModelElement[] getElementChildren() {
		return (IModelElement[]) getConfigs().toArray(
									   new IModelElement[getConfigs().size()]);
	}

	public void setAllowBeanDefinitionOverriding(
										boolean allowBeanDefinitionOverriding) {
		this.allowBeanDefinitionOverriding = allowBeanDefinitionOverriding;
		reset();
	}

	public boolean isAllowBeanDefinitionOverriding() {
		return allowBeanDefinitionOverriding;
	}

	public void setIncomplete(boolean isIncomplete) {
		this.isIncomplete = isIncomplete;
	}

	public boolean isIncomplete() {
		return isIncomplete;
	}

	public void addConfig(String configName) {
		if (configName.length() > 0 && !configNames.contains(configName)) {
			configNames.add(configName);
			reset();
		}
	}

	public boolean hasConfig(String configName) {
		return configNames.contains(configName);
	}

	public boolean hasConfig(IFile file) {
		return configNames.contains(file.getProjectRelativePath().toString());
	}

	public void removeConfig(String configName) {
		configNames.remove(configName);
		reset();
	}

	public Collection getConfigs() {
		return configNames;
	}

	public boolean hasBean(String name) {
		return getBeansMap().containsKey(name);
	}

	public IBean getBean(String name) {
		return (IBean) getBeansMap().get(name);
	}

	public Collection getBeans() {
		return getBeansMap().values();
	}

	public void replaceConfig(String origFileName, String newFileName) {
		removeConfig(origFileName);
		addConfig(newFileName);
		reset();
    }

	public boolean isBeanClass(String className) {
		return getBeanClassesMap().containsKey(className);
	}

	public Collection getBeanClasses() {
		return getBeanClassesMap().keySet();
	}

	public Collection getBeans(String className) {
		if (isBeanClass(className)) {
			return (Collection) getBeanClassesMap().get(className);
		}
		return Collections.EMPTY_LIST;
	}

	public String toString() {
		return getElementName() + ": " + configNames.toString();
	}

	/**
	 * Returns lazily initialized map with all beans defined in this config set.
	 */
	private Map getBeansMap() {
		if (beansMap == null) {
			beansMap = new HashMap();
			Iterator iter = configNames.iterator();
			while (iter.hasNext()) {
				String configName = (String) iter.next();
				IBeansConfig config = BeansModelUtils.getConfig(configName, this);
				if (config != null) {
	
					// Add beans to map
					Iterator beans = config.getBeans().iterator();
					while (beans.hasNext()) {
						IBean bean = (IBean) beans.next();
						if (allowBeanDefinitionOverriding ||
								 !beansMap.containsKey(bean.getElementName())) {
							beansMap.put(bean.getElementName(), bean);
						}
					}
				}
			}
		}
		return beansMap;
	}

	/**
	 * Returns lazily initialized map with all bean classes used in this config
	 * set.
	 */
	private Map getBeanClassesMap() {
		if (beanClassesMap == null) {
			beanClassesMap = new HashMap();
			Iterator beans = getBeansMap().values().iterator();
			while (beans.hasNext()) {
				IBean bean = (IBean) beans.next();
				addBeanClassToMap(bean);
				Iterator innerBeans = bean.getInnerBeans().iterator();
				while (innerBeans.hasNext()) {
					IBean innerBean = (IBean) innerBeans.next();
					addBeanClassToMap(innerBean);
				}
			}
		}
		return beanClassesMap;
	}

	private void addBeanClassToMap(IBean bean) {

		// Get name of bean class - strip name of any inner class
		String className = bean.getClassName();
		if (className != null) {
			int pos = className.indexOf('$');
			if  (pos > 0) {
				className = className.substring(0, pos);
			}

			// Maintain a list of bean names within every entry in the
			// bean class map
			List beanClassBeans = (List) beanClassesMap.get(className);
			if (beanClassBeans == null) {
				beanClassBeans = new ArrayList();
				beanClassesMap.put(className, beanClassBeans);
			}
			beanClassBeans.add(bean);
		}
	}
}
