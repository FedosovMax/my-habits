package com.maksym.habits.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "Habits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString
public class Habit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // SQLite AUTOINCREMENT
    private Integer id;

    // nullable INTEGERs (0/1 in DB). Use Boolean to preserve nullability.
    @Column(name = "archived")
    private Boolean archived;

    @Column(name = "color")
    private Integer color;

    @Column(name = "description")
    private String description;

    @Column(name = "freq_den")
    private Integer freqDen;

    @Column(name = "freq_num")
    private Integer freqNum;

    @Column(name = "highlight")
    private Boolean highlight;

    @Column(name = "name")
    private String name;

    @Column(name = "position")
    private Integer position;

    @Column(name = "reminder_hour")
    private Integer reminderHour;

    @Column(name = "reminder_min")
    private Integer reminderMin;

    // NOT NULL with default 127
    @Column(name = "reminder_days", nullable = false)
    @Builder.Default
    private Integer reminderDays = 127;

    // NOT NULL with default 0
    @Column(name = "type", nullable = false)
    @Builder.Default
    private Integer type = 0;

    // NOT NULL with default 0
    @Column(name = "target_type", nullable = false)
    @Builder.Default
    private Integer targetType = 0;

    // REAL NOT NULL with default 0
    @Column(name = "target_value", nullable = false)
    @Builder.Default
    private Double targetValue = 0.0;

    // TEXT NOT NULL with default ""
    @Column(name = "unit", nullable = false)
    @Builder.Default
    private String unit = "";

    @Column(name = "question")
    private String question;

    @Column(name = "uuid")
    private String uuid;
}
