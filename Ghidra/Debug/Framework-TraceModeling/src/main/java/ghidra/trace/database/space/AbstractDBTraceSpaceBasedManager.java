/* ###
 * IP: GHIDRA
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
package ghidra.trace.database.space;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import db.DBHandle;
import db.DBRecord;
import generic.CatenatedCollection;
import ghidra.dbg.target.TargetRegisterContainer;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.Language;
import ghidra.trace.database.*;
import ghidra.trace.database.thread.DBTraceThreadManager;
import ghidra.trace.model.stack.TraceObjectStackFrame;
import ghidra.trace.model.stack.TraceStackFrame;
import ghidra.trace.model.target.TraceObject;
import ghidra.trace.model.thread.TraceObjectThread;
import ghidra.trace.model.thread.TraceThread;
import ghidra.trace.util.TraceAddressSpace;
import ghidra.util.LockHold;
import ghidra.util.Msg;
import ghidra.util.database.*;
import ghidra.util.database.annot.*;
import ghidra.util.exception.VersionException;
import ghidra.util.task.TaskMonitor;

public abstract class AbstractDBTraceSpaceBasedManager<M extends DBTraceSpaceBased>
		implements DBTraceManager {
	protected static final AddressSpace NO_ADDRESS_SPACE = Address.NO_ADDRESS.getAddressSpace();

	@DBAnnotatedObjectInfo(version = 0)
	public static class DBTraceSpaceEntry extends DBAnnotatedObject {
		static final String SPACE_COLUMN_NAME = "Space";
		static final String THREAD_COLUMN_NAME = "Thread";
		static final String FRAME_COLUMN_NAME = "Frame";

		@DBAnnotatedColumn(SPACE_COLUMN_NAME)
		static DBObjectColumn SPACE_COLUMN;
		@DBAnnotatedColumn(THREAD_COLUMN_NAME)
		static DBObjectColumn THREAD_COLUMN;
		@DBAnnotatedColumn(FRAME_COLUMN_NAME)
		static DBObjectColumn FRAME_COLUMN;

		@DBAnnotatedField(column = SPACE_COLUMN_NAME)
		private String spaceName;
		@DBAnnotatedField(column = THREAD_COLUMN_NAME)
		private long threadKey;
		@DBAnnotatedField(column = FRAME_COLUMN_NAME)
		private int frameLevel;

		DBTraceSpaceBased space;

		public DBTraceSpaceEntry(DBCachedObjectStore<?> store, DBRecord record) {
			super(store, record);
		}

		void set(String spaceName, long threadKey, int frameLevel) {
			this.spaceName = spaceName;
			this.threadKey = threadKey;
			this.frameLevel = frameLevel;
			update(SPACE_COLUMN, THREAD_COLUMN, FRAME_COLUMN);
		}

		public long getThreadKey() {
			return threadKey;
		}

		public int getFrameLevel() {
			return frameLevel;
		}
	}

	protected final String name;
	protected final DBHandle dbh;
	protected final ReadWriteLock lock;
	protected final Language baseLanguage;
	protected final DBTrace trace;
	protected final DBTraceThreadManager threadManager;

	protected final DBCachedObjectStore<DBTraceSpaceEntry> spaceStore;
	// Note: use tree map so traversal is ordered by address space
	protected final Map<AddressSpace, M> memSpaces = new TreeMap<>();
	// Note: can use hash map here. I see no need to order these spaces
	protected final Map<Pair<TraceThread, Integer>, M> regSpaces = new HashMap<>();
	protected final Map<TraceObject, M> regSpacesByObject = new HashMap<>();

	protected final Collection<M> memSpacesView =
		Collections.unmodifiableCollection(memSpaces.values());
	protected final Collection<M> regSpacesView =
		Collections.unmodifiableCollection(regSpaces.values());
	protected final Collection<M> allSpacesView =
		new CatenatedCollection<>(memSpacesView, regSpacesView);

	public AbstractDBTraceSpaceBasedManager(String name, DBHandle dbh, DBOpenMode openMode,
			ReadWriteLock lock, TaskMonitor monitor, Language baseLanguage, DBTrace trace,
			DBTraceThreadManager threadManager) throws IOException, VersionException {
		this.name = name;
		this.dbh = dbh;
		this.lock = lock;
		this.baseLanguage = baseLanguage;
		this.trace = trace;
		this.threadManager = threadManager;

		DBCachedObjectStoreFactory factory = trace.getStoreFactory();

		spaceStore = factory.getOrCreateCachedStore(name + "Spaces", DBTraceSpaceEntry.class,
			DBTraceSpaceEntry::new, true);
	}

	protected String tableName(AddressSpace space, long threadKey, int frameLevel) {
		return DBTraceUtils.tableName(name, space, threadKey, frameLevel);
	}

	@SuppressWarnings("unchecked")
	protected void loadSpaces() throws VersionException, IOException {
		for (DBTraceSpaceEntry ent : spaceStore.asMap().values()) {
			AddressFactory addressFactory = trace.getBaseAddressFactory();
			AddressSpace space;
			if (NO_ADDRESS_SPACE.getName().equals(ent.spaceName)) {
				space = NO_ADDRESS_SPACE;
			}
			else {
				space = addressFactory.getAddressSpace(ent.spaceName);
			}
			if (space == null) {
				Msg.error(this, "Space " + ent.spaceName + " does not exist in trace (language=" +
					baseLanguage + ").");
			}
			else if (space.isRegisterSpace()) {
				if (threadManager == null) {
					Msg.error(this, "Register spaces are not allowed without a thread manager.");
					continue;
				}
				TraceThread thread = threadManager.getThread(ent.threadKey);
				M regSpace;
				if (ent.space == null) {
					regSpace = createRegisterSpace(space, thread, ent);
				}
				else {
					regSpace = (M) ent.space;
				}
				regSpaces.put(ImmutablePair.of(thread, ent.getFrameLevel()), regSpace);
			}
			else {
				M memSpace;
				if (ent.space == null) {
					memSpace = createSpace(space, ent);
				}
				else {
					memSpace = (M) ent.space;
				}
				memSpaces.put(space, memSpace);
			}
		}
	}

	protected M getForSpace(AddressSpace space, boolean createIfAbsent) {
		trace.assertValidSpace(Objects.requireNonNull(space));
		if (!space.isMemorySpace() && !space.isRegisterSpace() &&
			space != Address.NO_ADDRESS.getAddressSpace()) {
			throw new IllegalArgumentException(
				"Space must be a memory, register, or NO_ADDRESS space");
		}
		if (!createIfAbsent) {
			try (LockHold hold = LockHold.lock(lock.readLock())) {
				return memSpaces.get(space);
			}
		}
		try (LockHold hold = LockHold.lock(lock.writeLock())) {
			return memSpaces.computeIfAbsent(space, s -> {
				// NOTE: Require caller to start transaction
				try {
					DBTraceSpaceEntry ent = spaceStore.create();
					ent.set(space.getName(), -1, 0);
					return createSpace(space, ent);
				}
				catch (VersionException e) {
					throw new AssertionError(e);
				}
				catch (IOException e) {
					dbError(e);
					return null;
				}
			});
		}
	}

	protected M getForRegisterSpace(TraceThread thread, int frameLevel, boolean createIfAbsent) {
		trace.getThreadManager().assertIsMine(thread);
		if (trace.getObjectManager().hasSchema()) {
			return getForRegisterSpaceObjectThread((TraceObjectThread) thread, frameLevel,
				createIfAbsent);
		}
		Pair<TraceThread, Integer> frame = ImmutablePair.of(thread, frameLevel);
		if (!createIfAbsent) {
			try (LockHold hold = LockHold.lock(lock.readLock())) {
				return regSpaces.get(frame);
			}
		}
		try (LockHold hold = LockHold.lock(lock.writeLock())) {
			return regSpaces.computeIfAbsent(frame, t -> {
				AddressSpace regSpace = baseLanguage.getAddressFactory().getRegisterSpace();
				try {
					DBTraceSpaceEntry ent = spaceStore.create();
					ent.set(regSpace.getName(), thread.getKey(), frameLevel);
					return createRegisterSpace(regSpace, thread, ent);
				}
				catch (VersionException e) {
					throw new AssertionError(e);
				}
				catch (IOException e) {
					dbError(e);
					return null;
				}
			});
		}
	}

	protected M getForRegisterSpace(TraceStackFrame frame, boolean createIfAbsent) {
		if (frame instanceof TraceObjectStackFrame objFrame) {
			// Use frameLevel = 0, because we're already in the frame
			// so, no wild cards between here and registers
			return getForRegisterSpace(objFrame.getObject(), 0, createIfAbsent);
		}
		return getForRegisterSpace(frame.getStack().getThread(), frame.getLevel(), createIfAbsent);
	}

	private M doGetForRegisterSpaceFoundContainer(TraceObject object, TraceObject objRegs,
			boolean createIfAbsent) {
		String name = objRegs.getCanonicalPath().toString();
		if (!createIfAbsent) {
			try (LockHold hold = LockHold.lock(lock.readLock())) {
				AddressSpace as = trace.getBaseAddressFactory().getAddressSpace(name);
				if (as == null) {
					// TODO: Would like to cache this, but answer is likely to change
					return null;
				}
				M space = getForSpace(as, createIfAbsent);
				if (space == null) {
					return null;
				}
				synchronized (regSpacesByObject) {
					regSpacesByObject.put(object, space);
				}
				return space;
			}
		}
		try (LockHold hold = LockHold.lock(lock.writeLock())) {
			AddressSpace as = trace.getMemoryManager()
					.getOrCreateOverlayAddressSpace(name,
						trace.getBaseAddressFactory().getRegisterSpace());
			M space = getForSpace(as, createIfAbsent);
			synchronized (regSpacesByObject) {
				regSpacesByObject.put(object, space);
			}
			return space;
		}
	}

	protected M getForRegisterSpaceObjectThread(TraceObjectThread thread, int frameLevel,
			boolean createIfAbsent) {
		return getForRegisterSpace(thread.getObject(), frameLevel, createIfAbsent);
	}

	protected M getForRegisterSpace(TraceObject object, int frameLevel, boolean createIfAbsent) {
		synchronized (regSpacesByObject) {
			M space = regSpacesByObject.get(object);
			if (space != null) {
				return space;
			}
		}
		// It's not critical that we hold the regSpacesByObject the whole time.
		// If a second has to compute, too, aww well.
		try (LockHold hold = LockHold.lock(createIfAbsent ? lock.writeLock() : lock.readLock())) {
			if (object.getTargetSchema().getInterfaces().contains(TargetRegisterContainer.class)) {
				return doGetForRegisterSpaceFoundContainer(object, object, createIfAbsent);
			}
			TraceObject objRegs = object.queryRegisterContainer(frameLevel);
			if (objRegs != null) {
				return doGetForRegisterSpaceFoundContainer(object, objRegs, createIfAbsent);
			}
			return null;
		}
	}

	public DBTrace getTrace() {
		return trace;
	}

	public ReadWriteLock getLock() {
		return lock;
	}

	public Language getBaseLanguage() {
		return baseLanguage;
	}

	public M get(TraceAddressSpace space, boolean createIfAbsent) {
		TraceThread thread = space.getThread();
		if (thread != null) {
			return getForRegisterSpace(thread, space.getFrameLevel(), createIfAbsent);
		}
		return getForSpace(space.getAddressSpace(), createIfAbsent);
	}

	public Collection<M> getActiveSpaces() {
		return allSpacesView;
	}

	public Collection<M> getActiveMemorySpaces() {
		return memSpacesView;
	}

	public Collection<M> getActiveRegisterSpaces() {
		return regSpacesView;
	}

	protected abstract M createSpace(AddressSpace space, DBTraceSpaceEntry ent)
			throws VersionException, IOException;

	protected abstract M createRegisterSpace(AddressSpace space, TraceThread thread,
			DBTraceSpaceEntry ent) throws VersionException, IOException;

	@Override
	public void dbError(IOException e) {
		trace.dbError(e);
	}

	@Override
	public void invalidateCache(boolean all) {
		try (LockHold hold = LockHold.lock(lock.writeLock())) {
			spaceStore.invalidateCache();
			// TODO: Need to do a real delta here, not blow away and remake
			// Currently, object identities are not preserved by this operation
			memSpaces.clear();
			regSpaces.clear();
			loadSpaces();
			for (M m : memSpaces.values()) {
				m.invalidateCache();
			}
			for (M r : regSpaces.values()) {
				r.invalidateCache();
			}
		}
		catch (VersionException e) {
			throw new AssertionError(e);
		}
		catch (IOException e) {
			dbError(e);
		}
	}
}
