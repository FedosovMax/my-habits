package com.maksym.habits.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(
        name = "Repetitions",
        uniqueConstraints = @UniqueConstraint(
                name = "idx_repetitions_habit_timestamp",
                columnNames = {"habit", "timestamp"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
@ToString
public class Repetition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // SQLite AUTOINCREMENT
    private Integer id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "habit", nullable = false,
            foreignKey = @ForeignKey(name = "fk_repetitions_habit"))
    private Habit habit;

    @Column(name = "timestamp", nullable = false)
    private Long timestamp; // Stored as INTEGER in SQLite (epoch millis/seconds)

    @Column(name = "value", nullable = false)
    private Integer value;

    @Column(name = "notes")
    private String notes;
}
