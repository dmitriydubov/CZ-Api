# 🏷️ CrptApi - Клиент для API Честного знака

<div align="center">
  <img src="https://img.shields.io/badge/Java-11%2B-007396?style=for-the-badge&logo=java" alt="Java 11+">
  <img src="https://img.shields.io/badge/Gradle-8A2BE2?style=for-the-badge&logo=gradle" alt="Gradle">
</div>

## 🛠️ Основные возможности

- **Потокобезопасность** — гарантированная корректная работа в многопоточной среде
- **Rate Limiting** — интеллектуальное ограничение частоты запросов к API
- **Автоаутентификация** — автоматическое управление токенами доступа
- **JSON сериализация** — эффективная обработка данных через Jackson
- **Поддержка окружений** — гибкое переключение между prod и demo

## 📌 Основная функциональность

- **Принимает Java-объект документа**

- **Подпись передается отдельным параметром**

- **Автоматическая сериализация в JSON**

- **Готов к масштабированию функционала**

- **Реализовано юнит-тестирование**

## 🛠 Техническая реализация

| Аспект          | Реализация                 | Примечания                      |
|-----------------|----------------------------|---------------------------------|
| HTTP Client     | Java 11 HttpClient         | Встроенное решение Java         |
| JSON Processing | Jackson Databind 2.15+     | Сериализация/десериализация     |
| Thread Safety   | ReentrantLock + Token Bucket | Гарантированная потокобезопасность |
#
