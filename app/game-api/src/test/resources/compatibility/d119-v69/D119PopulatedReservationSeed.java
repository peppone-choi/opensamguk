import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import opensamguk.infra.persistence.ReservedTurnRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Compiled and run ONLY with the separately verified immutable old runtime classpath. */
public final class D119PopulatedReservationSeed {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("D119_ISOLATED_URL");
        if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/d119_[a-f0-9]+(?:\\?[^\\s]*)?"))
            throw new IllegalArgumentException("isolated loopback D119 database required");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode contract = mapper.readTree(Files.readString(Path.of(args[0])));
        if (!"CONTROLLED_POPULATED_OLD_V69".equals(contract.path("caseKind").asText()))
            throw new IllegalArgumentException("explicit controlled populated case required");
        int world = contract.path("worldId").asInt();
        int general = contract.path("generalId").asInt();
        if (world != 1 || general != 1001 || contract.path("expectedPopulatedRows").asInt() != 2)
            throw new IllegalArgumentException("fixed owned fixture identities required");
        DriverManagerDataSource ds = new DriverManagerDataSource(url,
            System.getenv("D119_ISOLATED_USER"), System.getenv("D119_ISOLATED_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        if (jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE world_id=1", Integer.class) != 0)
            throw new IllegalStateException("immutable old empty queue baseline was changed");
        if (jdbc.queryForObject("SELECT count(*) FROM general WHERE world_id=1 AND id=1001", Integer.class) != 1)
            throw new IllegalStateException("fixed public source general is absent");
        ReservedTurnRepository producer = new ReservedTurnRepository(new NamedParameterJdbcTemplate(ds));
        // WorldId is an inline value class in the old ABI. Do not load a current producer.
        Method reserve = Arrays.stream(ReservedTurnRepository.class.getMethods())
            .filter(m -> m.getName().startsWith("reserve-") && !m.getName().contains("$default")
                && m.getParameterCount() == 7 && m.getParameterTypes()[0] == int.class)
            .findFirst().orElseThrow();
        for (JsonNode row : contract.path("reservations")) {
            reserve.invoke(producer, world, general, row.path("slot").asInt(),
                row.path("actionCode").asText(), mapper.writeValueAsString(row.path("arg")),
                row.path("brief").asText(), null);
        }
        int count = jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE world_id=1", Integer.class);
        if (count != 2) throw new IllegalStateException("controlled producer must persist exactly two rows");
        System.out.println("D119_CONTROLLED_OLD_PRODUCER " + mapper.writeValueAsString(java.util.Map.of(
            "caseKind", "CONTROLLED_POPULATED_OLD_V69", "actualCount", count,
            "producerMethod", reserve.getName(), "producerClassOrigin",
            ReservedTurnRepository.class.getProtectionDomain().getCodeSource().getLocation().toString(),
            "reservations", contract.path("reservations"))));
    }
}
