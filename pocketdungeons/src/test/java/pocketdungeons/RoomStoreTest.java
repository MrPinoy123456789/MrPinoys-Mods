package pocketdungeons;

import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Regression for {@link RoomStore}'s persistence half (M44.1): the
 * backup-then-atomic-write guarantee, a corrupted file being caught and
 * logged rather than thrown, and {@link RoomStore#restoreFromBackup}
 * actually restoring from the backup file. Exercised through the
 * {@code Path}-taking overloads added alongside this test, so no
 * {@code MinecraftServer} is needed: the capture/place half of the class
 * (the {@code ServerLevel}-writing half) is out of reach for this headless
 * suite and is not covered here.
 */
public class RoomStoreTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    public static void main(String[] args) throws IOException {
        testSaveThenLoadRoundTrips();
        testSaveBacksUpThePreviousLiveFile();
        testCorruptedLiveFileReadsAsNullNotThrown();
        testRestoreFromBackupRestoresThePriorTag();
        testRestoreFromBackupFalseWithNoBackup();
        testResetBacksUpAndRemovesLiveFile();
        testResetFalseWithNoSavedRoom();
        testSaveReportsSuccessAndFailure();
        System.out.println("RoomStoreTest passed");
    }

    /**
     * (M63) A save says whether it landed.
     *
     * <p>Before this, {@link RoomStore#save} swallowed its {@code IOException}
     * and returned void, so a caller about to clear the room cell could not
     * find out that the room it was about to destroy had not been written. The
     * failure is provoked the one way a plain temp directory allows: the
     * destination path is occupied by a directory, so
     * {@code Files.createDirectories} and the write cannot produce a regular
     * file there.
     */
    private static void testSaveReportsSuccessAndFailure() throws IOException {
        Path dir = freshDir();
        check(RoomStore.save(dir, OWNER, tagged("good")), "a save that lands reports success");

        Path blocked = freshDir().resolve("blocked");
        Files.createDirectories(blocked.resolve(OWNER + ".dat"));
        check(!RoomStore.save(blocked, OWNER, tagged("doomed")),
                "a save that cannot write its file reports failure rather than swallowing it");
    }

    private static Path freshDir() throws IOException {
        return Files.createTempDirectory("roomstore-test");
    }

    private static CompoundTag tagged(String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", marker);
        return tag;
    }

    private static void testSaveThenLoadRoundTrips() throws IOException {
        Path dir = freshDir();
        RoomStore.save(dir, OWNER, tagged("first"));
        CompoundTag loaded = RoomStore.load(dir, OWNER);
        check(loaded != null, "a saved room loads back");
        check("first".equals(loaded.getStringOr("marker", "")), "the loaded tag is the one saved");
    }

    private static void testSaveBacksUpThePreviousLiveFile() throws IOException {
        Path dir = freshDir();
        RoomStore.save(dir, OWNER, tagged("first"));
        check(RoomStore.backupTime(dir, OWNER) == null, "no backup exists before a second save");
        RoomStore.save(dir, OWNER, tagged("second"));
        check(RoomStore.backupTime(dir, OWNER) != null, "the first save's file is backed up by the second");
        CompoundTag live = RoomStore.load(dir, OWNER);
        check("second".equals(live.getStringOr("marker", "")), "the live file holds the newest save");
    }

    /** A live file that is not valid compressed NBT is caught and logged, not thrown. */
    private static void testCorruptedLiveFileReadsAsNullNotThrown() throws IOException {
        Path dir = freshDir();
        Files.createDirectories(dir);
        Files.write(dir.resolve(OWNER + ".dat"), new byte[]{1, 2, 3, 4, 5});
        CompoundTag loaded = RoomStore.load(dir, OWNER);
        check(loaded == null, "a corrupted live file reads as null instead of throwing");
    }

    private static void testRestoreFromBackupRestoresThePriorTag() throws IOException {
        Path dir = freshDir();
        RoomStore.save(dir, OWNER, tagged("first"));
        RoomStore.save(dir, OWNER, tagged("second"));
        boolean restored = RoomStore.restoreFromBackup(dir, OWNER);
        check(restored, "a backup exists to restore from");
        CompoundTag live = RoomStore.load(dir, OWNER);
        check("first".equals(live.getStringOr("marker", "")), "restoring brings the backed-up tag back live");
        // PD-style guarantee: restoring is itself routed through save(), so it
        // leaves a fresh backup (of "second", the tag it just overwrote) behind.
        check(RoomStore.backupTime(dir, OWNER) != null, "restoring leaves a fresh backup in place");
    }

    private static void testRestoreFromBackupFalseWithNoBackup() throws IOException {
        Path dir = freshDir();
        check(!RoomStore.restoreFromBackup(dir, OWNER), "nothing to restore from an empty directory");
        RoomStore.save(dir, OWNER, tagged("only"));
        check(!RoomStore.restoreFromBackup(dir, OWNER), "one save alone leaves no backup yet");
    }

    private static void testResetBacksUpAndRemovesLiveFile() throws IOException {
        Path dir = freshDir();
        RoomStore.save(dir, OWNER, tagged("kept"));
        boolean didReset = RoomStore.reset(dir, OWNER);
        check(didReset, "a saved room existed to reset");
        check(RoomStore.load(dir, OWNER) == null, "the live room is gone after a reset");
        check(RoomStore.restoreFromBackup(dir, OWNER), "the reset room backed itself up first");
        check("kept".equals(RoomStore.load(dir, OWNER).getStringOr("marker", "")),
                "the pre-reset room is recoverable from that backup");
    }

    private static void testResetFalseWithNoSavedRoom() throws IOException {
        Path dir = freshDir();
        check(!RoomStore.reset(dir, OWNER), "nothing to reset with no saved room");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
