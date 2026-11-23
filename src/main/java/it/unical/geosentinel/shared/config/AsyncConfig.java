// Configurazione dell'esecuzione asincrona (@Async). Definisce due executor separati: uno per le
// notifiche degli SOS e uno per le email del reset della password, così un server SMTP lento non ritarda
// mai le notifiche di emergenza. Entrambi copiano nel thread di lavoro il contesto di log (MDC) di chi
// ha inviato il compito, in modo che i log asincroni mantengano il traceId della richiesta.
package it.unical.geosentinel.shared.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Abilita {@code @Async} e definisce gli executor dedicati. L'inizializzazione degli executor la fa
 * Spring, perché sono bean: non serve chiamare {@code initialize()} a mano.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    /**
     * Executor per le notifiche degli SOS (push ai soccorritori). Con la coda piena la politica
     * {@link ThreadPoolExecutor.CallerRunsPolicy} esegue il compito nel thread chiamante: la notifica
     * arriva più lentamente ma non viene mai scartata. Allo spegnimento attende fino a 60 secondi che i
     * compiti in corso finiscano.
     *
     * @return l'executor con 4 thread di base, fino a 8, e una coda di 100 compiti
     */
    @Bean(name = "sosNotificationExecutor")
    public ThreadPoolTaskExecutor sosNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("sos-notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }

    /**
     * Executor per le email con il codice di reset della password. Ha un solo thread, così i codici
     * partono nell'ordine delle richieste; il volume è minimo (al massimo 3 richieste all'ora per
     * indirizzo email). È separato da {@link #sosNotificationExecutor()} perché un SMTP lento non deve
     * rallentare le notifiche di emergenza.
     *
     * @return l'executor a thread singolo con una coda di 100 compiti
     */
    @Bean(name = "passwordResetMailExecutor")
    public ThreadPoolTaskExecutor passwordResetMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pwreset-mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }

    /**
     * Decoratore che porta il contesto MDC del thread che invia il compito nel thread che lo esegue.
     */
    private static class MdcTaskDecorator implements TaskDecorator {
        /**
         * Cattura l'MDC al momento dell'invio e lo installa durante l'esecuzione. Alla fine ripristina
         * l'MDC che il thread aveva prima: con la CallerRunsPolicy il compito può girare nel thread della
         * richiesta, e svuotarne l'MDC farebbe perdere il traceId ai log successivi.
         *
         * @param runnable compito originale
         * @return il compito avvolto
         */
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> submitterContext = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    setOrClear(submitterContext);
                    runnable.run();
                } finally {
                    setOrClear(previous);
                }
            };
        }

        /**
         * Installa il contesto indicato, oppure svuota l'MDC se il contesto è {@code null}.
         *
         * @param context mappa da installare, può essere {@code null}
         */
        private static void setOrClear(Map<String, String> context) {
            if (context == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
        }
    }
}
