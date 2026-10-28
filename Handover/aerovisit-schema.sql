-- aerovisit.admin_notification definition

CREATE TABLE `admin_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `is_read` bit(1) NOT NULL,
  `message` varchar(255) NOT NULL,
  `target_url` varchar(255) DEFAULT NULL,
  `type` varchar(40) NOT NULL,
  `actor` varchar(120) DEFAULT NULL,
  `dedupe_key` varchar(160) DEFAULT NULL,
  `resolved` tinyint(1) NOT NULL DEFAULT '0',
  `seen` tinyint(1) NOT NULL DEFAULT '0',
  `visitor_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKdyrju3iy1pys8d6ri0wf1thgf` (`dedupe_key`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.announcement definition

CREATE TABLE `announcement` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` bit(1) NOT NULL,
  `announcement_date` date NOT NULL,
  `message` varchar(2000) NOT NULL,
  `title` varchar(120) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.blacklisted_faces definition

CREATE TABLE `blacklisted_faces` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `banned_at` datetime(6) DEFAULT NULL,
  `expires_at` date DEFAULT NULL,
  `face_encoding` text,
  `image_url` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `reason` varchar(255) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `phone` varchar(30) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.booking_slot definition

CREATE TABLE `booking_slot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `max_capacity` int NOT NULL,
  `slot_date` date DEFAULT NULL,
  `time_window` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=36 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.chat_logs definition

CREATE TABLE `chat_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `timestamp` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_chat_logs_session` (`session_id`,`timestamp`)
) ENGINE=InnoDB AUTO_INCREMENT=51 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- aerovisit.exhibit_photo definition

CREATE TABLE `exhibit_photo` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `filename` varchar(80) NOT NULL,
  `upload_date` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKgcoicmrj5ei8gcl043wua6sjh` (`filename`)
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.guideline definition

CREATE TABLE `guideline` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `description` varchar(1000) NOT NULL,
  `display_order` int NOT NULL,
  `icon` varchar(40) NOT NULL,
  `title` varchar(80) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.hero_slide definition

CREATE TABLE `hero_slide` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `caption` varchar(90) DEFAULT NULL,
  `display_order` int NOT NULL,
  `filename` varchar(80) NOT NULL,
  `kicker` varchar(40) DEFAULT NULL,
  `upload_date` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK8f2k6hci0hqcvf1aj3gpiixy` (`filename`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.museum_closure definition

CREATE TABLE `museum_closure` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `closure_date` date NOT NULL,
  `reason` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK9v4n9cptdnbu9oyu4tca3nmd0` (`closure_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.museum_employee definition

CREATE TABLE `museum_employee` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `default_shift` varchar(255) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `job_title` varchar(100) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `role` varchar(255) DEFAULT NULL,
  `working_days` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.museum_zone definition

CREATE TABLE `museum_zone` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `is_active` bit(1) NOT NULL,
  `name` varchar(60) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKn45pg4u03jvgabxp75vuv8hu2` (`name`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.portal_settings definition

CREATE TABLE `portal_settings` (
  `id` bigint NOT NULL,
  `contact_email` varchar(150) DEFAULT NULL,
  `contact_phone` varchar(30) DEFAULT NULL,
  `contact_phone_note` varchar(160) DEFAULT NULL,
  `facebook_url` varchar(300) DEFAULT NULL,
  `instagram_url` varchar(300) DEFAULT NULL,
  `promo_video_url` varchar(200) DEFAULT NULL,
  `x_url` varchar(300) DEFAULT NULL,
  `ai_enabled` tinyint(1) DEFAULT '1',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.staff_leave definition

CREATE TABLE `staff_leave` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `end_date` date DEFAULT NULL,
  `staff_name` varchar(255) DEFAULT NULL,
  `start_date` date DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.staff_schedule definition

CREATE TABLE `staff_schedule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `archived` tinyint(1) NOT NULL DEFAULT '0',
  `duty_date` date DEFAULT NULL,
  `role` varchar(255) DEFAULT NULL,
  `roster_notified` tinyint(1) NOT NULL DEFAULT '0',
  `shift_time` varchar(255) DEFAULT NULL,
  `staff_name` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.staff_user definition

CREATE TABLE `staff_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `password` varchar(100) NOT NULL,
  `role` varchar(20) NOT NULL,
  `username` varchar(120) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKl5f8rn52rcqittbblcvouw8u9` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.time_window_config definition

CREATE TABLE `time_window_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `end_time` time(6) NOT NULL,
  `name` varchar(40) NOT NULL,
  `start_time` time(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.visitor definition

CREATE TABLE `visitor` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `affiliation` varchar(255) DEFAULT NULL,
  `age` int DEFAULT NULL,
  `booked_group_size` int DEFAULT NULL,
  `check_in_time` datetime(6) DEFAULT NULL,
  `check_out_time` datetime(6) DEFAULT NULL,
  `checked_in_by` varchar(255) DEFAULT NULL,
  `companion_names` text,
  `contact_number` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `entry_denial_reason` varchar(500) DEFAULT NULL,
  `entry_denied_at` datetime(6) DEFAULT NULL,
  `entry_denied_by` varchar(255) DEFAULT NULL,
  `event_justification` varchar(1000) DEFAULT NULL,
  `face_encoding` text,
  `feedback` varchar(500) DEFAULT NULL,
  `feedback_sentiment` varchar(20) DEFAULT NULL,
  `full_name` varchar(255) DEFAULT NULL,
  `group_size` int NOT NULL,
  `id_card_image_url` varchar(255) DEFAULT NULL,
  `id_image_url` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `ocr_name_match` tinyint(1) NOT NULL DEFAULT '0',
  `phone` varchar(255) DEFAULT NULL,
  `purpose` varchar(255) DEFAULT NULL,
  `qr_code_url` varchar(255) DEFAULT NULL,
  `rating` int DEFAULT NULL,
  `reference_id` varchar(20) DEFAULT NULL,
  `reference_number` varchar(255) DEFAULT NULL,
  `rejection_reason` varchar(500) DEFAULT NULL,
  `reminder_sent` bit(1) NOT NULL,
  `secure_token` varchar(255) DEFAULT NULL,
  `sex` varchar(255) DEFAULT NULL,
  `special_consideration` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `time_window` varchar(255) DEFAULT NULL,
  `topic_category` varchar(30) DEFAULT NULL,
  `visit_date` date DEFAULT NULL,
  `visit_time` time(6) DEFAULT NULL,
  `visitor_type` varchar(255) DEFAULT NULL,
  `zone_name` varchar(60) DEFAULT NULL,
  `possible_duplicate_of` varchar(30) DEFAULT NULL,
  `possible_ban_match` varchar(300) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK7bqgxw31t4oup9gqfckjnc4nh` (`reference_id`),
  UNIQUE KEY `UKarxq48gwfmju1hvipflg6def0` (`secure_token`)
) ENGINE=InnoDB AUTO_INCREMENT=20 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- aerovisit.visitor_tally_archive definition

CREATE TABLE `visitor_tally_archive` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tally_month` int NOT NULL,
  `visitors` int NOT NULL,
  `tally_year` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK2lt7su9mvhgobhe0rwhgkn8gr` (`tally_year`,`tally_month`)
) ENGINE=InnoDB AUTO_INCREMENT=49 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;