package com.maksym.habits.service;

import com.maksym.habits.model.Habit;
import com.maksym.habits.repository.HabitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class HabitService {

    private final HabitRepository repository;

    public Habit create(Habit habit) {
        return repository.save(habit);
    }

    public Habit update(Integer id, Habit habit) {
        Habit existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Habit not found with id " + id));

        // Copy updatable fields
        existing.setArchived(habit.getArchived());
        existing.setColor(habit.getColor());
        existing.setDescription(habit.getDescription());
        existing.setFreqDen(habit.getFreqDen());
        existing.setFreqNum(habit.getFreqNum());
        existing.setHighlight(habit.getHighlight());
        existing.setName(habit.getName());
        existing.setPosition(habit.getPosition());
        existing.setReminderHour(habit.getReminderHour());
        existing.setReminderMin(habit.getReminderMin());
        existing.setReminderDays(habit.getReminderDays());
        existing.setType(habit.getType());
        existing.setTargetType(habit.getTargetType());
        existing.setTargetValue(habit.getTargetValue());
        existing.setUnit(habit.getUnit());
        existing.setQuestion(habit.getQuestion());
        existing.setUuid(habit.getUuid());

        return repository.save(existing);
    }

    public Optional<Habit> get(Integer id) {
        return repository.findById(id);
    }

    public List<Habit> getAll() {
        return repository.findAll();
    }
}
