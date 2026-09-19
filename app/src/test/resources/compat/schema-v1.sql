CREATE TABLE IF NOT EXISTS `Timetable` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `year` INTEGER NOT NULL, `semester` INTEGER NOT NULL, `start` TEXT NOT NULL, `weekCount` INTEGER NOT NULL, `times` TEXT NOT NULL, PRIMARY KEY(`id`));
CREATE TABLE IF NOT EXISTS `Course` (`id` TEXT NOT NULL, `timetableId` TEXT NOT NULL, `name` TEXT NOT NULL, `teacher` TEXT NOT NULL, `room` TEXT NOT NULL, `weekday` INTEGER NOT NULL, `periods` TEXT NOT NULL, `weeks` TEXT NOT NULL, `note` TEXT NOT NULL, `sourceId` TEXT, `edited` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`));
CREATE INDEX IF NOT EXISTS `index_Course_timetableId` ON `Course` (`timetableId`);
INSERT INTO Timetable VALUES ('autumn','秋冬学期',2026,1,'2026-08-24',20,'');
INSERT INTO Timetable VALUES ('spring','春夏学期',2026,2,'2027-03-01',18,'');
INSERT INTO Course VALUES ('manual','autumn','数学','测试教师','B101',1,'3,4','4,6','手动调整','source-a',1,0);
INSERT INTO Course VALUES ('removed','autumn','英语','测试教师','G411',2,'1,2','1-16','已删除','source-b',0,1);
INSERT INTO Course VALUES ('local','spring','本地课程','','J110',3,'5,6','1-8','本地创建',NULL,0,0);
PRAGMA user_version=1;
