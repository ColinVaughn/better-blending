package dev.betterblending;

import dev.betterblending.backend.Backend;
import dev.betterblending.backend.TerrainTexture;
import net.minecraft.core.SectionPos;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.BitSet;

/** Sparse 16-cubed pages, uploaded in 64x64 patches instead of transferring the whole volume. */
final class TerrainVolume implements AutoCloseable {
    static final int PAGE_LIMIT = 1024, ATLAS_SIZE = 2048;
    final TerrainTexture blocks, colors;
    final TerrainTexture index;
    final int size, sections, capacity;
    int originX, originY, originZ;
    private final Long2IntOpenHashMap slots = new Long2IntOpenHashMap();
    private final long[] keys = new long[PAGE_LIMIT];
    private final BitSet dirty = new BitSet(), pinned = new BitSet();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet rejected = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final TerrainSections.Data[] owners = new TerrainSections.Data[PAGE_LIMIT];
    private final long[] published = new long[PAGE_LIMIT];
    private long revision = 1;
    private boolean indexDirty;

    TerrainVolume(int requestedSize) {
        sections = Math.max(4, Math.min(16, requestedSize / 16));
        size = sections * 16;
        capacity = Math.min(PAGE_LIMIT, sections * sections * sections);
        int atlasHeight = (capacity + 31) / 32 * 64;
        var backend = Backend.get();
        blocks = backend.createTexture("better_blending:volume_blocks", ATLAS_SIZE, atlasHeight, false);
        colors = backend.createTexture("better_blending:volume_colors", ATLAS_SIZE, atlasHeight, false);
        index = backend.createTexture("better_blending:volume_index", sections * sections, sections, false);
        slots.defaultReturnValue(-1);
        index.fill(0, 0, sections * sections, sections, 0);
        index.upload();
    }

    void move(int x, int y, int z) {
        if (x == originX && y == originY && z == originZ) return;
        originX = x; originY = y; originZ = z;
        revision++;
        index.fill(0, 0, sections * sections, sections, 0);
        for (var entry : slots.long2IntEntrySet()) writeIndex(entry.getLongKey(), entry.getIntValue() + 1);
        indexDirty = true;
    }

    boolean contains(int x, int y, int z) {
        return within(x, originX, size) && within(y, originY, size) && within(z, originZ, size);
    }

    private static boolean within(int value, int start, int length) {
        return value >= start && value < start + length;
    }

    /* The page holding a block, or -1 when the block is outside the volume or has no page. */
    private int slotAt(int x, int y, int z) {
        return contains(x, y, z) ? slots.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4)) : -1;
    }

    static int localIndex(int x, int y, int z) { return (x & 15) | ((z & 15) << 4) | ((y & 15) << 8); }

    int get(int x, int y, int z) {
        int slot = slotAt(x, y, z);
        if (slot < 0) return 0;
        int local = localIndex(x, y, z);
        return blocks.get((slot % 32) * 64 + (local & 63), (slot / 32) * 64 + (local >> 6));
    }

    void set(int x, int y, int z, int value, int tint) {
        if (!contains(x, y, z)) return;
        long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        int slot = slots.get(key);
        if (slot < 0 && (value & 4095) == 0) return;
        if (slot < 0) slot = allocate(key);
        if (slot < 0) return;
        int local = localIndex(x, y, z), px = slot % 32 * 64 + (local & 63), py = slot / 32 * 64 + (local >> 6);
        if (blocks.get(px, py) == value && colors.get(px, py) == tint) return;
        blocks.set(px, py, value);
        colors.set(px, py, tint);
        dirty.set(slot);
    }

    private int allocate(long key) {
        int existing = slots.get(key);
        if (existing >= 0) return existing;
        if (rejected.contains(key)) return -1;
        int slot = slots.size();
        if (slot == capacity) {
            double farthest = distance(key);
            slot = -1;
            for (int i = 0; i < capacity; i++) {
                double distance = distance(keys[i]);
                if (!pinned.get(i) && distance > farthest) { farthest = distance; slot = i; }
            }
            if (slot < 0) { rejected.add(key); return -1; }
            slots.remove(keys[slot]);
            writeIndex(keys[slot], 0);
            revision++;
        }
        keys[slot] = key;
        slots.put(key, slot);
        owners[slot] = null;
        clearPage(slot);
        writeIndex(key, slot + 1);
        return slot;
    }

    private void clearPage(int slot) {
        blocks.fill(slot % 32 * 64, slot / 32 * 64, 64, 64, 0);
        colors.fill(slot % 32 * 64, slot / 32 * 64, 64, 64, 0);
        dirty.set(slot);
    }

    boolean changed(TerrainSections.Data data) {
        int slot = slots.get(SectionPos.asLong(data.x() >> 4, data.y() >> 4, data.z() >> 4));
        return slot >= 0 && owners[slot] != null && owners[slot] != data;
    }

    void invalidateDonors(java.util.List<TerrainSections.Data> ready) {
        var affected = new BitSet();
        // A formerly visible neighbor can still own a stale page. Release it too,
        // so the updated halo can replace it even when that neighbor is now culled.
        for (var data : ready) if (changed(data)) markNeighborhood(affected, data);
        for (int slot = affected.nextSetBit(0); slot >= 0; slot = affected.nextSetBit(slot + 1)) {
            owners[slot] = null;
            clearPage(slot);
        }
        revision++;
    }

    private void markNeighborhood(BitSet affected, TerrainSections.Data data) {
        for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            int slot = slots.get(SectionPos.asLong((data.x() >> 4) + x, (data.y() >> 4) + y, (data.z() >> 4) + z));
            if (slot >= 0) affected.set(slot);
        }
    }

    void beginPublication() { pinned.clear(); rejected.clear(); }

    void prepare(java.util.List<TerrainSections.Data> ready) {
        ready.removeIf(data -> !contains(data.x(), data.y(), data.z()));
        ready.sort(java.util.Comparator.comparingDouble(data -> distance(SectionPos.asLong(data.x() >> 4, data.y() >> 4, data.z() >> 4))));
        beginPublication();
        if (ready.stream().anyMatch(this::changed)) invalidateDonors(ready);
        for (var data : ready) publish(data);
    }

    int pendingUploads() { return dirty.cardinality(); }

    void publish(TerrainSections.Data data) {
        if (!contains(data.x(), data.y(), data.z())) return;
        int slot = allocate(SectionPos.asLong(data.x() >> 4, data.y() >> 4, data.z() >> 4));
        if (slot < 0) return;
        pinned.set(slot);
        pinDonors(data);
        if (owners[slot] == data && published[slot] == revision) return;
        if (owners[slot] != data) {
            clearPage(slot);
            owners[slot] = data;
        }
        int width = 16 + data.radius() * 2;
        int[] entries = data.voxels();
        for (int i = 0; i < entries.length; i += 3) {
            int index = entries[i];
            int x = data.x() + index % width - data.radius();
            int z = data.z() + index / width % width - data.radius();
            int y = data.y() + index / (width * width) - data.radius();
            if (contains(x, y, z)) writeEntry(slot, x, y, z, entries[i + 1], entries[i + 2]);
        }
        published[slot] = revision;
    }

    private void pinDonors(TerrainSections.Data data) {
        int halo = data.radius() > 0 ? 1 : 0;
        for (int y = -halo; y <= halo; y++) for (int z = -halo; z <= halo; z++) for (int x = -halo; x <= halo; x++) {
            int donor = slotAt(data.x() + x * 16, data.y() + y * 16, data.z() + z * 16);
            if (donor >= 0) pinned.set(donor);
        }
    }

    private void writeEntry(int slot, int x, int y, int z, int block, int color) {
        long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        int target = slots.get(key);
        // Donor halos fill missing pages, never overwrite another section's complete snapshot.
        if (target != slot && target >= 0 && owners[target] != null) return;
        set(x, y, z, block, color);
        if (target < 0) {
            target = slots.get(key);
            if (target >= 0) pinned.set(target);
        }
    }

    void addFlags(int x, int y, int z, int flags, boolean replace) {
        int slot = slotAt(x, y, z);
        if (slot < 0) return;
        int local = localIndex(x, y, z), px = slot % 32 * 64 + (local & 63), py = slot / 32 * 64 + (local >> 6);
        int value = blocks.get(px, py);
        blocks.set(px, py, (replace ? value & ~12288 : value) | flags);
        dirty.set(slot);
    }

    private double distance(long key) {
        double x = SectionPos.x(key) * 16 + 8 - (originX + size / 2);
        double y = SectionPos.y(key) * 16 + 8 - (originY + size / 2);
        double z = SectionPos.z(key) * 16 + 8 - (originZ + size / 2);
        return x * x + y * y + z * z;
    }

    private void writeIndex(long key, int value) {
        int x = SectionPos.x(key) - (originX >> 4), y = SectionPos.y(key) - (originY >> 4), z = SectionPos.z(key) - (originZ >> 4);
        if (!within(x, 0, sections) || !within(y, 0, sections) || !within(z, 0, sections)) return;
        index.set(x + z * sections, y, value);
        indexDirty = true;
    }

    void upload() {
        if (dirty.isEmpty() && !indexDirty) return;
        Backend.get().uploadBatch(() -> {
            for (int slot = dirty.nextSetBit(0); slot >= 0;) {
                int x = slot % 32 * 64, y = slot / 32 * 64;
                int end = Math.min((slot / 32 + 1) * 32, dirty.nextClearBit(slot));
                int width = (end - slot) * 64;
                blocks.upload(x, y, width, 64);
                colors.upload(x, y, width, 64);
                slot = dirty.nextSetBit(end);
            }
            dirty.clear();
            if (indexDirty) { index.upload(); indexDirty = false; }
        });
    }

    @Override public void close() { blocks.close(); colors.close(); index.close(); }
}
