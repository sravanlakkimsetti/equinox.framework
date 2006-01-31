/*******************************************************************************
 * Copyright (c) 2005, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.internal.baseadaptor;

import java.io.*;
import java.util.Dictionary;
import org.eclipse.core.runtime.adaptor.LocationManager;
import org.eclipse.osgi.baseadaptor.*;
import org.eclipse.osgi.baseadaptor.hooks.DataHook;
import org.eclipse.osgi.baseadaptor.hooks.StorageHook;
import org.eclipse.osgi.framework.adaptor.*;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.internal.core.*;
import org.eclipse.osgi.framework.util.KeyedElement;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.BundleException;
import org.osgi.framework.Version;

public class BaseStorageHook implements StorageHook {
	public static final String KEY = BaseStorageHook.class.getName();
	public static final int HASHCODE = KEY.hashCode();
	public static final int DEL_BUNDLE_STORE = 0x01;
	public static final int DEL_GENERATION = 0x02;
	private static final int STORAGE_VERSION = 1;

	/** bundle's file name */
	private String fileName;
	/** native code paths for this BundleData */
	private String[] nativePaths;
	/** bundle generation */
	private int generation = 1;
	/** Is bundle a reference */
	private boolean reference;

	private BaseData bundleData;
	private BaseStorage storage;
	private File bundleStore;
	private File dataStore;

	public BaseStorageHook(BaseStorage storage) {
		this.storage = storage;
	}

	public int getStorageVersion() {
		return STORAGE_VERSION;
	}

	public StorageHook create(BaseData bundledata) throws BundleException {
		BaseStorageHook storageHook = new BaseStorageHook(storage);
		storageHook.bundleData = bundledata;
		return storageHook;
	}

	public void initialize(Dictionary manifest) throws BundleException {
		BaseStorageHook.loadManifest(bundleData, manifest);
	}

	static void loadManifest(BaseData target, Dictionary manifest) throws BundleException {
		try {
			target.setVersion(Version.parseVersion((String) manifest.get(Constants.BUNDLE_VERSION)));
		} catch (IllegalArgumentException e) {
			target.setVersion(new InvalidVersion((String) manifest.get(Constants.BUNDLE_VERSION)));
		}
		ManifestElement[] bsnHeader = ManifestElement.parseHeader(Constants.BUNDLE_SYMBOLICNAME, (String) manifest.get(Constants.BUNDLE_SYMBOLICNAME));
		int bundleType = 0;
		if (bsnHeader != null) {
			target.setSymbolicName(bsnHeader[0].getValue());
			String singleton = bsnHeader[0].getDirective(Constants.SINGLETON_DIRECTIVE);
			if (singleton == null)
				singleton = bsnHeader[0].getAttribute(Constants.SINGLETON_DIRECTIVE);
			if ("true".equals(singleton)) //$NON-NLS-1$
				bundleType |= BundleData.TYPE_SINGLETON;
		}
		target.setClassPathString((String) manifest.get(Constants.BUNDLE_CLASSPATH));
		target.setActivator((String) manifest.get(Constants.BUNDLE_ACTIVATOR));
		String host = (String) manifest.get(Constants.FRAGMENT_HOST);
		if (host != null) {
			bundleType |= BundleData.TYPE_FRAGMENT;
			ManifestElement[] hostElement = ManifestElement.parseHeader(Constants.FRAGMENT_HOST, host);
			if (Constants.getInternalSymbolicName().equals(hostElement[0].getValue()) || Constants.OSGI_SYSTEM_BUNDLE.equals(hostElement[0].getValue())) {
				String extensionType = hostElement[0].getDirective("extension"); //$NON-NLS-1$
				if (extensionType == null || extensionType.equals("framework")) //$NON-NLS-1$
					bundleType |= BundleData.TYPE_FRAMEWORK_EXTENSION;
				else
					bundleType |= BundleData.TYPE_BOOTCLASSPATH_EXTENSION;
			}
		}
		target.setType(bundleType);
		target.setExecutionEnvironment((String) manifest.get(Constants.BUNDLE_REQUIREDEXECUTIONENVIRONMENT));
		target.setDynamicImports((String) manifest.get(Constants.DYNAMICIMPORT_PACKAGE));
	}

	public StorageHook load(BaseData target, DataInputStream in) throws IOException {
		target.setLocation(AdaptorUtil.readString(in, false));
		target.setSymbolicName(AdaptorUtil.readString(in, false));
		target.setVersion(AdaptorUtil.loadVersion(in));
		target.setActivator(AdaptorUtil.readString(in, false));
		target.setClassPathString(AdaptorUtil.readString(in, false));
		target.setExecutionEnvironment(AdaptorUtil.readString(in, false));
		target.setDynamicImports(AdaptorUtil.readString(in, false));
		target.setStartLevel(in.readInt());
		target.setStatus(in.readInt());
		target.setType(in.readInt());
		target.setLastModified(in.readLong());
		target.setDirty(false); // make sure to reset the dirty bit;

		BaseStorageHook storageHook = new BaseStorageHook(storage);
		storageHook.bundleData = target;
		storageHook.generation = in.readInt();
		storageHook.reference = in.readBoolean();
		storageHook.fileName = getAbsolute(storageHook.reference, AdaptorUtil.readString(in, false));
		int nativePathCount = in.readInt();
		storageHook.nativePaths = nativePathCount > 0 ? new String[nativePathCount] : null;
		for (int i = 0; i < nativePathCount; i++)
			storageHook.nativePaths[i] = in.readUTF();
		return storageHook;
	}

	private String getAbsolute(boolean isReference, String path) {
		if (!isReference)
			return path;
		// fileName for bundles installed with reference URLs is stored relative to the install location
		File storedPath = new File(path);
		if (!storedPath.isAbsolute())
			// make sure it has the absolute location instead
			return new FilePath(storage.getInstallPath() + path).toString();
		return path;
	}

	public void save(DataOutputStream out) throws IOException {
		if (bundleData == null)
			throw new IllegalStateException();
		AdaptorUtil.writeStringOrNull(out, bundleData.getLocation());
		AdaptorUtil.writeStringOrNull(out, bundleData.getSymbolicName());
		AdaptorUtil.writeStringOrNull(out, bundleData.getVersion().toString());
		AdaptorUtil.writeStringOrNull(out, bundleData.getActivator());
		AdaptorUtil.writeStringOrNull(out, bundleData.getClassPathString());
		AdaptorUtil.writeStringOrNull(out, bundleData.getExecutionEnvironment());
		AdaptorUtil.writeStringOrNull(out, bundleData.getDynamicImports());
		DataHook[] hooks = bundleData.getAdaptor().getHookRegistry().getDataHooks();
		boolean forgetStartLevel = false;
		for (int i = 0; i < hooks.length && !forgetStartLevel; i++)
			forgetStartLevel = hooks[i].forgetStartLevelChange(bundleData, bundleData.getStartLevel());
		out.writeInt(!forgetStartLevel ? bundleData.getStartLevel() : 1);
		boolean forgetStatus = false;
		// see if we should forget the persistently started flag
		for (int i = 0; i < hooks.length && !forgetStatus; i++)
			forgetStatus = hooks[i].forgetStatusChange(bundleData, bundleData.getStatus());
		out.writeInt(!forgetStatus ? bundleData.getStatus() : (~Constants.BUNDLE_STARTED) & bundleData.getStatus());
		out.writeInt(bundleData.getType());
		out.writeLong(bundleData.getLastModified());

		out.writeInt(getGeneration());
		out.writeBoolean(isReference());
		String storedFileName = isReference() ? new FilePath(storage.getInstallPath()).makeRelative(new FilePath(getFileName())) : getFileName();
		AdaptorUtil.writeStringOrNull(out, storedFileName);
		if (nativePaths == null)
			out.writeInt(0);
		else {
			out.writeInt(nativePaths.length);
			for (int i = 0; i < nativePaths.length; i++)
				out.writeUTF(nativePaths[i]);
		}

	}

	public int getKeyHashCode() {
		return HASHCODE;
	}

	public boolean compare(KeyedElement other) {
		return other.getKey() == KEY;
	}

	public Object getKey() {
		return KEY;
	}

	public String getFileName() {
		return fileName;
	}

	public int getGeneration() {
		return generation;
	}

	public String[] getNativePaths() {
		return nativePaths;
	}

	public void setNativePaths(String[] nativePaths) {
		this.nativePaths = nativePaths;
	}

	public boolean isReference() {
		return reference;
	}

	public File getBundleStore() {
		if (bundleStore == null)
			bundleStore = new File(storage.getBundleStoreRoot(), String.valueOf(bundleData.getBundleID()));
		return bundleStore;
	}

	public File getDataFile(String path) {
		// lazily initialize dirData to prevent early access to configuration location
		if (dataStore == null)
			dataStore = new File(getBundleStore(), BaseStorage.DATA_DIR_NAME);
		if (path != null && !dataStore.exists() && (storage.isReadOnly() || !dataStore.mkdirs()))
			if (Debug.DEBUG && Debug.DEBUG_GENERAL)
				Debug.println("Unable to create bundle data directory: " + dataStore.getPath()); //$NON-NLS-1$
		return path == null ? dataStore : new File(dataStore, path);
	}

	void delete(boolean postpone, int type) throws IOException {
		File delete = null;
		switch (type) {
			case DEL_GENERATION :
				delete = getGenerationDir();
				break;
			case DEL_BUNDLE_STORE :
				delete = getBundleStore();
				break;
		}
		if (delete != null && delete.exists() && (postpone || !AdaptorUtil.rm(delete))) {
			/* create .delete */
			FileOutputStream out = new FileOutputStream(new File(delete, ".delete")); //$NON-NLS-1$
			out.close();
		}
	}

	File getGenerationDir() {
		return new File(getBundleStore(), String.valueOf(getGeneration()));
	}

	File getParentGenerationDir() {
		Location parentConfiguration = null;
		Location currentConfiguration = LocationManager.getConfigurationLocation();
		if (currentConfiguration != null && (parentConfiguration = currentConfiguration.getParentLocation()) != null)
			return new File(parentConfiguration.getURL().getFile(), FrameworkAdaptor.FRAMEWORK_SYMBOLICNAME + '/' + LocationManager.BUNDLES_DIR + '/' + bundleData.getBundleID() + '/' + getGeneration());
		return null;
	}

	File createGenerationDir() {
		File generationDir = getGenerationDir();
		if (!generationDir.exists() && (storage.isReadOnly() || !generationDir.mkdirs()))
			if (Debug.DEBUG && Debug.DEBUG_GENERAL)
				Debug.println("Unable to create bundle generation directory: " + generationDir.getPath()); //$NON-NLS-1$
		return generationDir;
	}

	public void setReference(boolean reference) {
		this.reference = reference;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public void copy(StorageHook storageHook) {
		if (!(storageHook instanceof BaseStorageHook))
			throw new IllegalArgumentException();
		BaseStorageHook hook = (BaseStorageHook) storageHook;
		bundleStore = hook.bundleStore;
		dataStore = hook.dataStore;
		generation = hook.generation + 1;
		// fileName and reference will be set by update
	}

	public void validate() throws IllegalArgumentException {
		// do nothing
	}

	public Dictionary getManifest(boolean firstLoad) throws BundleException {
		// do nothing
		return null;
	}
}
