/*******************************************************************************
 * Copyright (c) 2004, 2010 Spring IDE Developers
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Spring IDE Developers - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.eclipse.beans.core.internal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SafeRunner;
import org.springframework.ide.eclipse.beans.core.BeansCorePlugin;
import org.springframework.ide.eclipse.beans.core.internal.project.BeansProjectDescriptionReader;
import org.springframework.ide.eclipse.beans.core.internal.project.BeansProjectDescriptionWriter;
import org.springframework.ide.eclipse.beans.core.model.IBean;
import org.springframework.ide.eclipse.beans.core.model.IBeansConfig;
import org.springframework.ide.eclipse.beans.core.model.IBeansConfigEventListener;
import org.springframework.ide.eclipse.beans.core.model.IBeansConfigSet;
import org.springframework.ide.eclipse.beans.core.model.IBeansImport;
import org.springframework.ide.eclipse.beans.core.model.IBeansModel;
import org.springframework.ide.eclipse.beans.core.model.IBeansModelElementTypes;
import org.springframework.ide.eclipse.beans.core.model.IBeansProject;
import org.springframework.ide.eclipse.beans.core.model.IBeansConfig.Type;
import org.springframework.ide.eclipse.beans.core.model.locate.BeansConfigLocatorDefinition;
import org.springframework.ide.eclipse.beans.core.model.locate.BeansConfigLocatorFactory;
import org.springframework.ide.eclipse.beans.core.model.process.IBeansConfigPostProcessor;
import org.springframework.ide.eclipse.core.MarkerUtils;
import org.springframework.ide.eclipse.core.SpringCore;
import org.springframework.ide.eclipse.core.io.ExternalFile;
import org.springframework.ide.eclipse.core.model.AbstractResourceModelElement;
import org.springframework.ide.eclipse.core.model.ILazyInitializedModelElement;
import org.springframework.ide.eclipse.core.model.IModelElement;
import org.springframework.ide.eclipse.core.model.IModelElementVisitor;
import org.springframework.ide.eclipse.core.model.ISpringProject;
import org.springframework.util.ObjectUtils;

/**
 * This class holds information for a Spring Beans project. The information is lazily read from the corresponding
 * project description XML file defined in {@link IBeansProject#DESCRIPTION_FILE}.
 * <p>
 * The information can be persisted by calling the method {@link #saveDescription()}.
 * @author Torsten Juergeleit
 * @author Dave Watkins
 * @author Christian Dupuis
 */
public class BeansProject extends AbstractResourceModelElement implements IBeansProject, ILazyInitializedModelElement {

	private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();

	private final Lock r = rwl.readLock();

	private final Lock w = rwl.writeLock();

	protected volatile boolean modelPopulated = false;

	private final IProject project;

	protected volatile Set<String> configSuffixes = new LinkedHashSet<String>();

	/** the internal flag to specify if import processing is enabled */
	protected volatile boolean isImportsEnabled = DEFAULT_IMPORTS_ENABLED;

	/** Internal version number; intentionally set to lower value */
	protected volatile String version = "2.0.0";

	protected volatile Map<String, IBeansConfig> configs = new LinkedHashMap<String, IBeansConfig>();

	protected volatile Map<String, IBeansConfig> autoDetectedConfigs = new LinkedHashMap<String, IBeansConfig>();

	protected volatile Map<String, Set<String>> autoDetectedConfigsByLocator = new LinkedHashMap<String, Set<String>>();

	protected volatile Map<String, String> locatorByAutoDetectedConfig = new LinkedHashMap<String, String>();

	protected volatile Map<String, IBeansConfigSet> configSets = new LinkedHashMap<String, IBeansConfigSet>();

	protected volatile Map<String, IBeansConfigSet> autoDetectedConfigSets = new LinkedHashMap<String, IBeansConfigSet>();

	protected volatile Map<String, String> autoDetectedConfigSetsByLocator = new LinkedHashMap<String, String>();

	protected volatile IBeansConfigEventListener eventListener;

	public BeansProject(IBeansModel model, IProject project) {
		super(model, project.getName());
		this.project = project;
	}

	/**
	 * {@inheritDoc}
	 */
	public int getElementType() {
		return IBeansModelElementTypes.PROJECT_TYPE;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IModelElement[] getElementChildren() {
		Set<IModelElement> children = new LinkedHashSet<IModelElement>(getConfigs());
		children.addAll(getConfigSets());
		return children.toArray(new IModelElement[children.size()]);
	}

	/**
	 * {@inheritDoc}
	 */
	public IResource getElementResource() {
		return project;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isElementArchived() {
		return false;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void accept(IModelElementVisitor visitor, IProgressMonitor monitor) {
		// First visit this project
		if (!monitor.isCanceled() && visitor.visit(this, monitor)) {

			// Now ask this project's configs
			for (IBeansConfig config : getConfigs()) {
				config.accept(visitor, monitor);
				if (monitor.isCanceled()) {
					return;
				}
			}

			// Finally ask this project's config sets
			for (IBeansConfigSet configSet : getConfigSets()) {
				configSet.accept(visitor, monitor);
				if (monitor.isCanceled()) {
					return;
				}
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public IProject getProject() {
		return project;
	}

	/**
	 * Updates the list of config suffixes belonging to this project.
	 * <p>
	 * The modified project description has to be saved to disk by calling {@link #saveDescription()}.
	 * @param suffixes list of config suffixes
	 */
	public void setConfigSuffixes(Set<String> suffixes) {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			w.lock();
			configSuffixes.clear();
			configSuffixes.addAll(suffixes);
		}
		finally {
			w.unlock();
		}
	}

	public boolean addConfigSuffix(String suffix) {
		if (suffix != null && suffix.length() > 0) {
			if (!this.modelPopulated) {
				populateModel();
			}
			try {
				w.lock();
				if (!configSuffixes.contains(suffix)) {
					configSuffixes.add(suffix);
					return true;
				}
			}
			finally {
				w.unlock();
			}
		}
		return false;
	}

	public Set<String> getConfigSuffixes() {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			return Collections.unmodifiableSet(configSuffixes);
		}
		finally {
			r.unlock();
		}
	}

	/**
	 * @deprecated use {@link #getConfigSuffixes()} instead.
	 */
	@Deprecated
	public Set<String> getConfigExtensions() {
		return getConfigSuffixes();
	}

	public boolean hasConfigSuffix(String suffix) {
		try {
			r.lock();
			return getConfigSuffixes().contains(suffix);
		}
		finally {
			r.unlock();
		}
	}

	/**
	 * @deprecated use {@link #hasConfigSuffix(String)} instead.
	 */
	@Deprecated
	public boolean hasConfigExtension(String extension) {
		return hasConfigSuffix(extension);
	}

	/**
	 * Updates the list of configs (by name) belonging to this project. From all removed configs the Spring IDE problem
	 * markers are deleted.
	 * <p>
	 * The modified project description has to be saved to disk by calling {@link #saveDescription()}.
	 * @param configNames list of config names
	 */
	public void setConfigs(Set<String> configNames) {
		if (!this.modelPopulated) {
			populateModel();
		}
		List<IResource> deleteMarkersFrom = new ArrayList<IResource>();
		try {
			w.lock();
			// Look for removed configs and
			// 1. delete all problem markers from them
			// 2. remove config from any config set
			for (IBeansConfig config : configs.values()) {
				String configName = config.getElementName();
				if (!configNames.contains(configName)) {
					removeConfig(configName);

					// Defer deletion of problem markers until write lock is
					// released
					deleteMarkersFrom.add(config.getElementResource());
				}
			}

			// Create new list of configs
			configs.clear();
			for (String configName : configNames) {
				configs.put(configName, new BeansConfig(this, configName, Type.MANUAL));
			}
		}
		finally {
			w.unlock();
		}

		// Delete the problem markers after the write lock is released -
		// otherwise this may be interfering with a ResourceChangeListener
		// referring to this beans project
		for (IResource configResource : deleteMarkersFrom) {
			MarkerUtils.deleteMarkers(configResource, SpringCore.MARKER_ID);
		}
	}

	/**
	 * Adds the given beans config file's name to the list of configs.
	 * <p>
	 * The modified project description has to be saved to disk by calling {@link #saveDescription()}.
	 * @param file the config file to add
	 * @return <code>true</code> if config file was added to this project
	 */
	public boolean addConfig(IFile file, IBeansConfig.Type type) {
		return addConfig(getConfigName(file), type);
	}

	/**
	 * Adds the given beans config to the list of configs.
	 * <p>
	 * The modified project description has to be saved to disk by calling {@link #saveDescription()}.
	 * @param configName the config name to add
	 * @return <code>true</code> if config was added to this project
	 */
	public boolean addConfig(String configName, IBeansConfig.Type type) {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			w.lock();
			if (configName.length() > 0 && !configs.containsKey(configName)) {
				if (type == IBeansConfig.Type.MANUAL) {
					IBeansConfig config = new BeansConfig(this, configName, type);
					configs.put(configName, config);
					config.registerEventListener(eventListener);

					if (autoDetectedConfigs.containsKey(configName)) {
						autoDetectedConfigs.remove(configName);
						String locatorId = locatorByAutoDetectedConfig.remove(configName);
						if (locatorId != null && autoDetectedConfigsByLocator.containsKey(locatorId)) {
							autoDetectedConfigsByLocator.get(locatorId).remove(configName);
						}
					}
					return true;
				}
				else if (type == IBeansConfig.Type.AUTO_DETECTED && !autoDetectedConfigs.containsKey(configName)) {
					populateAutoDetectedConfigsAndConfigSets();
					return true;
				}
			}
		}
		finally {
			w.unlock();
		}
		return false;
	}

	/**
	 * Removes the given beans config from the list of configs and from all config sets.
	 * <p>
	 * The modified project description has to be saved to disk by calling {@link #saveDescription()}.
	 * @param file the config file to remove
	 * @return <code>true</code> if config was removed to this project
	 */
	public boolean removeConfig(IFile file) {
		if (file.getProject().equals(project)) {
			return removeConfig(file.getProjectRelativePath().toString());
		}

		// External configs only remove from all config sets
		return removeConfigFromConfigSets(file.getFullPath().toString());
	}

	/**
	 * Removes the given beans config from the list of configs and from all config sets.
	 * <p>
	 * The modified project description has to be saved to disk by calling {@link #saveDescription()}.
	 * @param configName the config name to remove
	 * @return <code>true</code> if config was removed to this project
	 */
	public boolean removeConfig(String configName) {
		if (hasConfig(configName)) {
			try {
				w.lock();
				IBeansConfig config = configs.remove(configName);
				IBeansConfig autoDetectedConfig = autoDetectedConfigs.remove(configName);
				if (config != null) {
					config.unregisterEventListener(eventListener);
				}
				if (autoDetectedConfig != null) {
					autoDetectedConfig.unregisterEventListener(eventListener);
				}
				String locatorId = locatorByAutoDetectedConfig.remove(configName);
				if (locatorId != null && autoDetectedConfigsByLocator.containsKey(locatorId)) {
					autoDetectedConfigsByLocator.get(locatorId).remove(configName);
				}
			}
			finally {
				w.unlock();
			}
			removeConfigFromConfigSets(configName);
			return true;
		}
		return false;
	}

	public boolean hasConfig(IFile file) {
		return hasConfig(getConfigName(file));
	}

	public boolean hasConfig(String configName) {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			return (configs.containsKey(configName) || autoDetectedConfigs.containsKey(configName));
		}
		finally {
			r.unlock();
		}
	}

	public IBeansConfig getConfig(IFile configFile, boolean includeImported) {
		Set<IBeansConfig> beansConfigs = getConfigs(configFile, includeImported);
		Iterator<IBeansConfig> iterator = beansConfigs.iterator();
		if (iterator.hasNext()) {
			return iterator.next();
		}
		return null;
	}

	public Set<IBeansConfig> getConfigs(IFile file, boolean includeImported) {
		Set<IBeansConfig> beansConfigs = new LinkedHashSet<IBeansConfig>();
		IBeansConfig beansConfig = getConfig(file);
		if (beansConfig != null) {
			beansConfigs.add(beansConfig);
		}

		// make sure that we run into the next block only if <import> support is enabled
		// not executing the block will safe lots of execution time as configuration files don't
		// need to get loaded.
		if ((isImportsEnabled() && includeImported) && getConfigs() != null) {
			try {
				r.lock();
				for (IBeansConfig bc : getConfigs()) {
					checkForImportedBeansConfig(file, bc, beansConfigs);
				}
			}
			finally {
				r.unlock();
			}
		}
		return beansConfigs;
	}

	private void checkForImportedBeansConfig(IFile file, IBeansConfig bc, Set<IBeansConfig> beansConfigs) {
		if (bc.getElementResource() != null && bc.getElementResource().equals(file)) {
			beansConfigs.add(bc);
		}

		for (IBeansImport bi : bc.getImports()) {
			for (IBeansConfig importedBc : bi.getImportedBeansConfigs()) {
				if (importedBc.getElementResource() != null && importedBc.getElementResource().equals(file)) {
					beansConfigs.add(importedBc);
				}
				for (IBeansImport iBi : importedBc.getImports()) {
					for (IBeansConfig iBc : iBi.getImportedBeansConfigs()) {
						checkForImportedBeansConfig(file, iBc, beansConfigs);
					}
				}
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public IBeansConfig getConfig(IFile file) {
		IBeansConfig config = getConfig(getConfigName(file));
		if (config == null) {
			if (!this.modelPopulated) {
				populateModel();
			}
			try {
				r.lock();
				for (IBeansConfig beansConfig : configs.values()) {
					if (beansConfig.getElementResource() != null && beansConfig.getElementResource().equals(file)) {
						return beansConfig;
					}
				}
			}
			finally {
				r.unlock();
			}

		}
		return config;
	}

	/**
	 * {@inheritDoc}
	 */
	public IBeansConfig getConfig(String configName) {
		if (configName != null && configName.length() > 0 && configName.charAt(0) == '/') {
			return BeansCorePlugin.getModel().getConfig(configName);
		}
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			if (configs.containsKey(configName)) {
				return configs.get(configName);
			}
			else if (autoDetectedConfigs.containsKey(configName)) {
				return autoDetectedConfigs.get(configName);
			}
			return null;
		}
		finally {
			r.unlock();
		}
	}

	public Set<String> getConfigNames() {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			Set<String> configNames = new LinkedHashSet<String>(configs.keySet());
			configNames.addAll(autoDetectedConfigs.keySet());
			return configNames;
		}
		finally {
			r.unlock();
		}
	}

	public Set<String> getManualConfigNames() {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			return new LinkedHashSet<String>(configs.keySet());
		}
		finally {
			r.unlock();
		}
	}

	public Set<String> getManualConfigSetNames() {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			return new LinkedHashSet<String>(configSets.keySet());
		}
		finally {
			r.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<IBeansConfig> getConfigs() {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			Set<IBeansConfig> beansConfigs = new LinkedHashSet<IBeansConfig>(configs.values());
			beansConfigs.addAll(autoDetectedConfigs.values());
			return beansConfigs;
		}
		finally {
			r.unlock();
		}
	}

	/**
	 * Updates the {@link BeansConfigSet}s defined within this project.
	 * <p>
	 * The modified project description has to be saved to disk by calling {@link #saveDescription()}.
	 * @param configSets list of {@link BeansConfigSet} instances
	 */
	public void setConfigSets(Set<IBeansConfigSet> configSets) {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			w.lock();
			this.configSets.clear();
			for (IBeansConfigSet configSet : configSets) {
				this.configSets.put(configSet.getElementName(), configSet);
			}
		}
		finally {
			w.unlock();
		}
	}

	public boolean addConfigSet(IBeansConfigSet configSet) {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			if (!configSets.values().contains(configSet)) {
				configSets.put(configSet.getElementName(), configSet);

				if (autoDetectedConfigSets.containsKey(configSet.getElementName())) {
					autoDetectedConfigSets.remove(configSet.getElementName());
					autoDetectedConfigSetsByLocator.remove(configSet.getElementName());
				}

				return true;
			}
		}
		finally {
			r.unlock();
		}
		return false;
	}

	public void removeConfigSet(String configSetName) {
		try {
			w.lock();
			configSets.remove(configSetName);
		}
		finally {
			w.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean hasConfigSet(String configSetName) {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			return configSets.containsKey(configSetName);
		}
		finally {
			r.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public IBeansConfigSet getConfigSet(String configSetName) {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			IBeansConfigSet set = configSets.get(configSetName);
			if (set != null) {
				return set;
			}
			return autoDetectedConfigSets.get(configSetName);
		}
		finally {
			r.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<IBeansConfigSet> getConfigSets() {
		if (!this.modelPopulated) {
			populateModel();
		}
		try {
			r.lock();
			Set<IBeansConfigSet> configSets = new LinkedHashSet<IBeansConfigSet>(this.configSets.values());
			configSets.addAll(autoDetectedConfigSets.values());
			return configSets;
		}
		finally {
			r.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isBeanClass(String className) {
		for (IBeansConfig config : getConfigs()) {
			if (config.isBeanClass(className)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<String> getBeanClasses() {
		Set<String> beanClasses = new LinkedHashSet<String>();
		for (IBeansConfig config : getConfigs()) {
			beanClasses.addAll(config.getBeanClasses());
		}
		return beanClasses;
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<IBean> getBeans(String className) {
		Set<IBean> beans = new LinkedHashSet<IBean>();
		for (IBeansConfig config : getConfigs()) {
			if (config.isBeanClass(className)) {
				beans.addAll(config.getBeans(className));
			}
		}
		return beans;
	}

	/**
	 * Writes the current project description to the corresponding XML file defined in
	 * {@link IBeansProject#DESCRIPTION_FILE}.
	 */
	public void saveDescription() {

		// We can't acquire the write lock here - otherwise this may be
		// interfering with a ResourceChangeListener referring to this beans
		// project
		BeansProjectDescriptionWriter.write(this);
	}

	/**
	 * Resets the internal data. Any further access to the data of this instance of {@link BeansProject} leads to
	 * reloading of this beans project's config description file.
	 */
	public void reset() {
		try {
			w.lock();
			this.modelPopulated = false;
			configSuffixes.clear();
			configs.clear();
			configSets.clear();
			autoDetectedConfigs.clear();
			autoDetectedConfigsByLocator.clear();
			locatorByAutoDetectedConfig.clear();
			autoDetectedConfigSets.clear();
			autoDetectedConfigSetsByLocator.clear();
		}
		finally {
			w.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof BeansProject)) {
			return false;
		}
		BeansProject that = (BeansProject) other;
		if (!ObjectUtils.nullSafeEquals(this.project, that.project))
			return false;
		return super.equals(other);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int hashCode() {
		int hashCode = ObjectUtils.nullSafeHashCode(project);
		return getElementType() * hashCode + super.hashCode();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String toString() {
		try {
			r.lock();
			return "Project=" + getElementName() + ", ConfigExtensions=" + configSuffixes + ", Configs="
					+ configs.values() + ", ConfigsSets=" + configSets;
		}
		finally {
			r.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isImportsEnabled() {
		return isImportsEnabled;
	}

	public void setImportsEnabled(boolean importEnabled) {
		this.isImportsEnabled = importEnabled;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isUpdatable() {
		IFile file = project.getProject().getFile(new Path(IBeansProject.DESCRIPTION_FILE));
		return !file.isReadOnly();
	}

	public void removeAutoDetectedConfigs(String locatorId) {
		try {
			w.lock();
			Set<String> configs = autoDetectedConfigsByLocator.get(locatorId);
			if (configs != null) {
				autoDetectedConfigsByLocator.remove(locatorId);
			}
			if (configs != null) {
				for (String configName : configs) {
					// Before actually removing make sure to delete ALL markers
					MarkerUtils.deleteAllMarkers(getConfig(configName).getElementResource(), SpringCore.MARKER_ID);

					// Remove the config from the internal list
					autoDetectedConfigs.remove(configName);
					locatorByAutoDetectedConfig.remove(configName);
				}
			}

			String configSet = autoDetectedConfigSetsByLocator.get(locatorId);
			if (configSets != null) {
				autoDetectedConfigSets.remove(configSet);
				autoDetectedConfigSetsByLocator.remove(configSet);
			}
		}
		finally {
			w.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isExternal() {
		return false;
	}

	private boolean removeConfigFromConfigSets(String configName) {
		if (!this.modelPopulated) {
			populateModel();
		}
		boolean hasRemoved = false;
		try {
			r.lock();
			for (IBeansConfigSet configSet : configSets.values()) {
				if (configSet.hasConfig(configName)) {
					((BeansConfigSet) configSet).removeConfig(configName);
					hasRemoved = true;
				}
			}
			for (IBeansConfigSet configSet : autoDetectedConfigSets.values()) {
				if (configSet.hasConfig(configName)) {
					((BeansConfigSet) configSet).removeConfig(configName);
					hasRemoved = true;
				}
			}
		}
		finally {
			r.unlock();
		}
		return hasRemoved;
	}

	/**
	 * Returns the config name from given file. If the file belongs to this project then the config name is the
	 * project-relative path of the given file otherwise it's the workspace-relative path with a leading '/'.
	 */
	private String getConfigName(IFile file) {
		String configName;
		if (file.getProject().equals(project.getProject()) && !(file instanceof ExternalFile)) {
			configName = file.getProjectRelativePath().toString();
		}
		else {
			configName = file.getFullPath().toString();
		}
		return configName;
	}

	/**
	 * Populate the project's model with the information read from project description (an XML file defined in
	 * {@link ISpringProject.DESCRIPTION_FILE}).
	 */
	private void populateModel() {
		try {
			w.lock();
			if (this.modelPopulated) {
				return;
			}
			this.eventListener = new DefaultBeansConfigEventListener();
			this.modelPopulated = true;

			BeansProjectDescriptionReader.read(this);

			// Remove all invalid configs from this project
			Set<IBeansConfig> configuredConfigs = new LinkedHashSet<IBeansConfig>(configs.values());
			for (IBeansConfig config : configuredConfigs) {
				if (config.getElementResource() == null || !config.getElementResource().exists()) {
					removeConfig(config.getElementName());
				}
			}

			// Add auto detected configs and config sets
			populateAutoDetectedConfigsAndConfigSets();

			// Remove all invalid config names from this project's config sets
			IBeansModel model = BeansCorePlugin.getModel();
			for (IBeansConfigSet configSet : configSets.values()) {
				for (String configName : configSet.getConfigNames()) {
					if (!hasConfig(configName) && model.getConfig(configName) == null) {
						((BeansConfigSet) configSet).removeConfig(configName);
					}
				}
			}

			for (IBeansConfig config : configs.values()) {
				config.registerEventListener(eventListener);
			}
		}
		finally {
			w.unlock();
		}
	}

	/**
	 * Runs the registered detectors and registers {@link IBeansConfig} and {@link IBeansConfigSet} with this project.
	 * <p>
	 * This method should only be called with having a write lock.
	 */
	private void populateAutoDetectedConfigsAndConfigSets() {

		for (IBeansConfig config : autoDetectedConfigs.values()) {
			config.unregisterEventListener(eventListener);
		}

		autoDetectedConfigs.clear();
		autoDetectedConfigsByLocator.clear();
		locatorByAutoDetectedConfig.clear();
		autoDetectedConfigSets.clear();
		autoDetectedConfigSetsByLocator.clear();

		// Add auto detected beans configs
		for (final BeansConfigLocatorDefinition locator : BeansConfigLocatorFactory.getBeansConfigLocatorDefinitions()) {
			if (locator.isEnabled(getProject()) && locator.getBeansConfigLocator().supports(getProject())) {
				final Map<String, IBeansConfig> detectedConfigs = new HashMap<String, IBeansConfig>();
				final String[] configSetName = new String[1];

				// Prevent extension contribution from crashing the model creation
				SafeRunner.run(new ISafeRunnable() {

					public void handleException(Throwable exception) {
						// nothing to handle here
					}

					public void run() throws Exception {
						Set<IFile> files = locator.getBeansConfigLocator().locateBeansConfigs(getProject(), null);
						for (IFile file : files) {
							BeansConfig config = new BeansConfig(BeansProject.this, file.getProjectRelativePath()
									.toString(), Type.AUTO_DETECTED);
							String configName = getConfigName(file);
							if (!hasConfig(configName)) {
								detectedConfigs.put(configName, config);
							}
						}
						if (files.size() > 1) {
							String configSet = locator.getBeansConfigLocator().getBeansConfigSetName(files);
							if (configSet.length() > 0) {
								configSetName[0] = configSet;
							}
						}
					}
				});

				if (detectedConfigs.size() > 0) {
					Set<String> configNamesByLocator = new LinkedHashSet<String>();

					for (Map.Entry<String, IBeansConfig> detectedConfig : detectedConfigs.entrySet()) {
						autoDetectedConfigs.put(detectedConfig.getKey(), detectedConfig.getValue());
						detectedConfig.getValue().registerEventListener(eventListener);
						configNamesByLocator.add(getConfigName((IFile) detectedConfig.getValue().getElementResource()));
						locatorByAutoDetectedConfig.put(getConfigName((IFile) detectedConfig.getValue()
								.getElementResource()), locator.getNamespaceUri() + "." + locator.getId());
					}
					autoDetectedConfigsByLocator.put(locator.getNamespaceUri() + "." + locator.getId(),
							configNamesByLocator);

					// Create a config set for auto detected configs if desired by the extension
					if (configSetName[0] != null && configSetName[0].length() > 0) {

						IBeansConfigSet configSet = new BeansConfigSet(this, configSetName[0], configNamesByLocator,
								IBeansConfigSet.Type.AUTO_DETECTED);

						// configure the created IBeansConfig
						locator.getBeansConfigLocator().configureBeansConfigSet(configSet);

						autoDetectedConfigSets.put(configSetName[0], configSet);
						autoDetectedConfigSetsByLocator.put(locator.getNamespaceUri() + "." + locator.getId(),
								configSetName[0]);
					}
				}
			}
		}
	}

	/**
	 * Default implementation of {@link IBeansConfigEventListener} that handles events and propagates those to
	 * {@link IBeansConfigSet}s and other {@link IBeansConfig}.
	 * @author Christian Dupuis
	 * @since 2.2.5
	 */
	class DefaultBeansConfigEventListener implements IBeansConfigEventListener {

		/**
		 * {@inheritDoc}
		 */
		public void onPostProcessorDetected(IBeansConfig config, IBeansConfigPostProcessor postProcessor) {
			for (IBeansProject project : BeansCorePlugin.getModel().getProjects()) {
				for (IBeansConfigSet configSet : project.getConfigSets()) {
					if (configSet.hasConfig((IFile) config.getElementResource())) {
						for (IBeansConfig configSetConfig : configSet.getConfigs()) {
							if (!configSetConfig.equals(config) && configSetConfig instanceof BeansConfig) {
								((BeansConfig) configSetConfig).addExternalPostProcessor(postProcessor, config);
							}
						}
					}
				}
			}
		}

		/**
		 * {@inheritDoc}
		 */
		public void onPostProcessorRemoved(IBeansConfig config, IBeansConfigPostProcessor postProcessor) {
			for (IBeansProject project : BeansCorePlugin.getModel().getProjects()) {
				for (IBeansConfigSet configSet : project.getConfigSets()) {
					if (configSet.hasConfig((IFile) config.getElementResource())) {
						for (IBeansConfig configSetConfig : configSet.getConfigs()) {
							if (!configSetConfig.equals(config) && configSetConfig instanceof BeansConfig) {
								((BeansConfig) configSetConfig).removeExternalPostProcessor(postProcessor, config);
							}
						}
					}
				}
			}
		}

		/**
		 * {@inheritDoc}
		 */
		public void onReadEnd(IBeansConfig config) {
		}

		/**
		 * {@inheritDoc}
		 */
		public void onReadStart(IBeansConfig config) {
		}

		/**
		 * {@inheritDoc}
		 */
		public void onReset(IBeansConfig config) {
			for (IBeansProject project : BeansCorePlugin.getModel().getProjects()) {
				for (IBeansConfigSet configSet : project.getConfigSets()) {
					if (configSet.hasConfig((IFile) config.getElementResource())) {
						if (configSet instanceof BeansConfigSet) {
							((BeansConfigSet) configSet).reset();
						}
					}
				}
			}
		}
	}

	public boolean isInitialized() {
		if (!this.modelPopulated) {
			return false;
		}
		try {
			r.lock();
			for (IBeansConfig config : configs.values()) {
				if (!((ILazyInitializedModelElement) config).isInitialized()) {
					return false;
				}
			}
			for (IBeansConfig config : autoDetectedConfigs.values()) {
				if (!((ILazyInitializedModelElement) config).isInitialized()) {
					return false;
				}
			}
			return true;
		}
		finally {
			r.unlock();
		}
	}

}
